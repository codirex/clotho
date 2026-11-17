package org.codirex.clotho.build

import java.io.File
import java.io.InputStream
import org.w3c.dom.Element

/** The published artifacts whose classes are compared; manifest entries must name one of them. */
internal val PUBLISHED_ARTIFACTS = setOf("clotho", "clotho-test", "clotho-android")

/** One reviewed and approved binary incompatibility. */
data class ApprovedBreak(
    val identity: BreakIdentity,
    val approval: String,
    val release: String,
    val rationale: String,
)

/**
 * Reads the version-controlled manifest of approved binary breaks.
 *
 * The format is deliberately strict: one `<break>` per incompatibility, every identifying attribute
 * required for its kind, no wildcards, no unknown attributes, no duplicates.
 */
object ApprovedBreakManifest {
    private val COMMON_ATTRIBUTES = setOf("artifact", "class", "kind", "change", "approval", "release")
    private val KIND_ATTRIBUTES: Map<MemberKind, Set<String>> =
        mapOf(
            MemberKind.CLASS to emptySet(),
            MemberKind.SUPERCLASS to setOf("name"),
            MemberKind.INTERFACE to setOf("name"),
            MemberKind.CONSTRUCTOR to setOf("parameters"),
            MemberKind.METHOD to setOf("name", "parameters", "returnType"),
            MemberKind.FIELD to setOf("name", "type"),
        )
    private val WILDCARD = Regex("[*?]")
    private val CHANGE_TYPE = Regex("[A-Z][A-Z0-9_]*")

    fun read(file: File): List<ApprovedBreak> = file.inputStream().use { parse(it, file.path) }

    fun parse(input: InputStream, origin: String): List<ApprovedBreak> {
        val root =
            try {
                secureDocumentBuilder().parse(input).documentElement
            } catch (e: Exception) {
                throw IllegalArgumentException(
                    "Cannot parse binary compatibility manifest $origin: ${e.message}",
                    e,
                )
            }

        val errors = mutableListOf<String>()
        val entries = mutableListOf<ApprovedBreak>()
        if (root.tagName != "approvedBreaks") {
            errors += "root element must be <approvedBreaks>, found <${root.tagName}>"
        } else {
            for ((index, element) in root.childElements().withIndex()) {
                if (element.tagName != "break") {
                    errors += "element #${index + 1}: unexpected <${element.tagName}>; only <break> is allowed"
                    continue
                }
                parseEntry(element, index + 1, errors)?.let { entries += it }
            }
            entries
                .groupBy { it.identity }
                .filterValues { it.size > 1 }
                .keys
                .forEach { errors += "Duplicate approved break: $it" }
        }

        require(errors.isEmpty()) {
            "Invalid binary compatibility manifest $origin:\n" + errors.joinToString("\n") { "  - $it" }
        }
        return entries
    }

    private fun parseEntry(element: Element, number: Int, errors: MutableList<String>): ApprovedBreak? {
        val className = element.getAttribute("class")
        val label = "break #$number" + if (className.isNotBlank()) " ($className)" else ""
        val problems = mutableListOf<String>()

        val present =
            (0 until element.attributes.length).map { element.attributes.item(it).nodeName }.toSet()
        val kind = MemberKind.values().firstOrNull { it.name == element.getAttribute("kind") }
        if (kind == null) {
            problems += "kind must be one of ${MemberKind.values().joinToString()}"
        } else {
            val allowed = COMMON_ATTRIBUTES + KIND_ATTRIBUTES.getValue(kind)
            val unknown = (present - allowed).sorted()
            if (unknown.isNotEmpty()) {
                problems += "unknown attribute(s) for kind $kind: ${unknown.joinToString()}"
            }
            val missing = (allowed - present).sorted()
            if (missing.isNotEmpty()) problems += "missing attribute(s): ${missing.joinToString()}"
        }

        for (name in present.sorted()) {
            val value = element.getAttribute(name)
            // An empty parameter list is a legitimate exact descriptor.
            if (value.isBlank() && name != "parameters") problems += "attribute $name must not be blank"
            if (WILDCARD.containsMatchIn(value)) {
                problems += "attribute $name contains a wildcard; only exact entries are allowed"
            }
        }

        val artifact = element.getAttribute("artifact")
        if (artifact.isNotBlank() && artifact !in PUBLISHED_ARTIFACTS) {
            problems += "unknown artifact '$artifact'; expected one of ${PUBLISHED_ARTIFACTS.joinToString()}"
        }
        val change = element.getAttribute("change")
        if (change.isNotBlank() && !CHANGE_TYPE.matches(change)) {
            problems += "change '$change' is not a JApiCmp compatibility change type (UPPER_SNAKE_CASE)"
        }

        val children = element.childElements()
        val rationales = children.filter { it.tagName == "rationale" }
        if (rationales.size != 1 || rationales.single().textContent.isBlank()) {
            problems += "exactly one non-blank <rationale> is required"
        }
        val unexpected = children.filter { it.tagName != "rationale" }.map { it.tagName }
        if (unexpected.isNotEmpty()) {
            problems += "unexpected child element(s): ${unexpected.joinToString { "<$it>" }}"
        }

        if (kind == null || problems.isNotEmpty()) {
            problems.mapTo(errors) { "$label: $it" }
            return null
        }

        val parameters =
            element.getAttribute("parameters").split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val identity =
            when (kind) {
                MemberKind.CLASS ->
                    BreakIdentity(artifact, className, kind, "", emptyList(), "", change)
                MemberKind.SUPERCLASS, MemberKind.INTERFACE ->
                    BreakIdentity(artifact, className, kind, element.getAttribute("name"), emptyList(), "", change)
                MemberKind.CONSTRUCTOR ->
                    BreakIdentity(artifact, className, kind, "<init>", parameters, "", change)
                MemberKind.METHOD ->
                    BreakIdentity(
                        artifact,
                        className,
                        kind,
                        element.getAttribute("name"),
                        parameters,
                        element.getAttribute("returnType"),
                        change,
                    )
                MemberKind.FIELD ->
                    BreakIdentity(
                        artifact,
                        className,
                        kind,
                        element.getAttribute("name"),
                        emptyList(),
                        element.getAttribute("type"),
                        change,
                    )
            }
        return ApprovedBreak(
            identity = identity,
            approval = element.getAttribute("approval"),
            release = element.getAttribute("release"),
            rationale = rationales.single().textContent.trim().replace(Regex("\\s+"), " "),
        )
    }
}
