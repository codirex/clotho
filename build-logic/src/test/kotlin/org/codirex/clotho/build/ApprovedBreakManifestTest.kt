package org.codirex.clotho.build

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ApprovedBreakManifestTest {
    private fun parse(xml: String): List<ApprovedBreak> =
        ApprovedBreakManifest.parse(xml.byteInputStream(), "test-manifest.xml")

    private fun manifest(vararg entries: String): String =
        "<approvedBreaks>" + entries.joinToString("") + "</approvedBreaks>"

    /** A valid CLASS entry; [overrides] replace attributes, a null value removes the attribute. */
    private fun entry(
        vararg overrides: Pair<String, String?>,
        rationale: String = "<rationale>Replaced by org.example.New.</rationale>",
    ): String {
        val attributes = LinkedHashMap(BASE)
        for ((key, value) in overrides) {
            if (value == null) attributes.remove(key) else attributes[key] = value
        }
        return "<break " +
            attributes.entries.joinToString(" ") { "${it.key}=\"${it.value}\"" } +
            ">" +
            rationale +
            "</break>"
    }

    private fun assertInvalid(xml: String, fragment: String) {
        val error = assertThrows<IllegalArgumentException> { parse(xml) }
        assertTrue(error.message!!.contains(fragment), "Expected '$fragment' in: ${error.message}")
    }

    @Test
    fun `an empty manifest approves nothing`() {
        assertTrue(parse("<approvedBreaks/>").isEmpty())
    }

    @Test
    fun `parses one entry per kind into exact identities`() {
        val parsed =
            parse(
                manifest(
                    entry(),
                    entry(
                        "class" to "org.example.Api",
                        "kind" to "SUPERCLASS",
                        "name" to "org.example.Base",
                        "change" to "SUPERCLASS_REMOVED",
                    ),
                    entry(
                        "class" to "org.example.Api",
                        "kind" to "INTERFACE",
                        "name" to "java.io.Closeable",
                        "change" to "INTERFACE_REMOVED",
                    ),
                    entry(
                        "class" to "org.example.Api",
                        "kind" to "CONSTRUCTOR",
                        "parameters" to "",
                        "change" to "CONSTRUCTOR_REMOVED",
                    ),
                    entry(
                        "class" to "org.example.Api",
                        "kind" to "METHOD",
                        "name" to "run",
                        "parameters" to "java.lang.String, long",
                        "returnType" to "void",
                        "change" to "METHOD_REMOVED",
                    ),
                    entry(
                        "class" to "org.example.Api",
                        "kind" to "FIELD",
                        "name" to "LIMIT",
                        "type" to "int",
                        "change" to "FIELD_REMOVED",
                    ),
                ),
            )

        assertEquals(6, parsed.size)
        assertEquals(
            BreakIdentity("clotho", "org.example.Gone", MemberKind.CLASS, "", emptyList(), "", "CLASS_REMOVED"),
            parsed[0].identity,
        )
        assertEquals(
            BreakIdentity(
                "clotho",
                "org.example.Api",
                MemberKind.CONSTRUCTOR,
                "<init>",
                emptyList(),
                "",
                "CONSTRUCTOR_REMOVED",
            ),
            parsed[3].identity,
        )
        assertEquals(
            BreakIdentity(
                "clotho",
                "org.example.Api",
                MemberKind.METHOD,
                "run",
                listOf("java.lang.String", "long"),
                "void",
                "METHOD_REMOVED",
            ),
            parsed[4].identity,
        )
        assertEquals("PR #1", parsed[4].approval)
        assertEquals("0.1.0-alpha03", parsed[4].release)
        assertEquals("Replaced by org.example.New.", parsed[4].rationale)
    }

    @Test
    fun `rejects an entry without a rationale`() {
        assertInvalid(manifest(entry(rationale = "")), "exactly one non-blank <rationale>")
        assertInvalid(
            manifest(entry(rationale = "<rationale>   </rationale>")),
            "exactly one non-blank <rationale>",
        )
    }

    @Test
    fun `rejects wildcards and package wide entries`() {
        assertInvalid(manifest(entry("class" to "org.example.*")), "wildcard")
        assertInvalid(
            manifest(
                entry(
                    "kind" to "METHOD",
                    "name" to "*",
                    "parameters" to "",
                    "returnType" to "void",
                ),
            ),
            "wildcard",
        )
    }

    @Test
    fun `rejects an unknown artifact`() {
        assertInvalid(manifest(entry("artifact" to "clotho-bom")), "unknown artifact")
    }

    @Test
    fun `rejects missing and unknown attributes for the kind`() {
        assertInvalid(
            manifest(
                entry("kind" to "METHOD", "parameters" to "", "returnType" to "void"),
            ),
            "missing attribute(s): name",
        )
        assertInvalid(manifest(entry("name" to "x")), "unknown attribute(s) for kind CLASS: name")
        assertInvalid(manifest(entry("release" to null)), "missing attribute(s): release")
    }

    @Test
    fun `rejects an invalid kind, change type or blank value`() {
        assertInvalid(manifest(entry("kind" to "PACKAGE")), "kind must be one of")
        assertInvalid(manifest(entry("change" to "removed")), "not a JApiCmp compatibility change type")
        assertInvalid(manifest(entry("approval" to " ")), "attribute approval must not be blank")
    }

    @Test
    fun `rejects duplicate entries`() {
        assertInvalid(manifest(entry(), entry()), "Duplicate approved break")
    }

    @Test
    fun `rejects unexpected elements and a wrong root`() {
        assertInvalid("<approvedBreaks><other/></approvedBreaks>", "only <break> is allowed")
        assertInvalid("<breaks/>", "root element must be <approvedBreaks>")
    }

    @Test
    fun `refuses documents with a DOCTYPE`() {
        assertInvalid("<!DOCTYPE approvedBreaks [<!ENTITY x \"y\">]><approvedBreaks/>", "Cannot parse")
    }

    private companion object {
        val BASE =
            linkedMapOf(
                "artifact" to "clotho",
                "class" to "org.example.Gone",
                "kind" to "CLASS",
                "change" to "CLASS_REMOVED",
                "approval" to "PR #1",
                "release" to "0.1.0-alpha03",
            )
    }
}
