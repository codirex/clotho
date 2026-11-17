package org.codirex.clotho.build

/** What a binary incompatibility is attached to inside one class. */
enum class MemberKind { CLASS, SUPERCLASS, INTERFACE, CONSTRUCTOR, METHOD, FIELD }

/**
 * The exact identity of one binary incompatibility, relative to the published baseline.
 *
 * Member data always describes the baseline (old) declaration: [parameters] and [type] (the return
 * type of a method, the type of a field) are the types the published artifact declared.
 */
data class BreakIdentity(
    val artifact: String,
    val className: String,
    val kind: MemberKind,
    val name: String,
    val parameters: List<String>,
    val type: String,
    val change: String,
) {
    override fun toString(): String = buildString {
        append(artifact).append(": ").append(className)
        when (kind) {
            MemberKind.CLASS -> Unit
            MemberKind.SUPERCLASS -> append(" superclass ").append(name)
            MemberKind.INTERFACE -> append(" interface ").append(name)
            MemberKind.CONSTRUCTOR -> append(" constructor(").append(parameters.joinToString(", ")).append(')')
            MemberKind.METHOD ->
                append(' ').append(type).append(' ').append(name)
                    .append('(').append(parameters.joinToString(", ")).append(')')
            MemberKind.FIELD -> append(" field ").append(type).append(' ').append(name)
        }
        append(" -> ").append(change)
    }

    /** A ready-to-edit manifest entry for this break; approval and rationale are placeholders. */
    fun manifestSnippet(release: String): String {
        val attributes = linkedMapOf("artifact" to artifact, "class" to className, "kind" to kind.name)
        when (kind) {
            MemberKind.CLASS -> Unit
            MemberKind.SUPERCLASS, MemberKind.INTERFACE -> attributes["name"] = name
            MemberKind.CONSTRUCTOR -> attributes["parameters"] = parameters.joinToString(",")
            MemberKind.METHOD -> {
                attributes["name"] = name
                attributes["parameters"] = parameters.joinToString(",")
                attributes["returnType"] = type
            }
            MemberKind.FIELD -> {
                attributes["name"] = name
                attributes["type"] = type
            }
        }
        attributes["change"] = change
        attributes["approval"] = "TODO: approving PR or release reference"
        attributes["release"] = release

        return buildString {
            appendLine("<break")
            attributes.forEach { (key, value) -> appendLine("    $key=\"${xmlEscape(value)}\"") }
            appendLine(">")
            appendLine("    <rationale>TODO: why this break is accepted, its impact and the migration path</rationale>")
            append("</break>")
        }
    }
}

private fun xmlEscape(value: String): String =
    value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
