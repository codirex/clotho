package org.codirex.clotho.build

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import org.gradle.api.GradleException
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class BinaryCompatibilityGateTest {
    private fun evaluate(breaks: Set<BreakIdentity>, vararg entries: ApprovedBreak): GateOutcome =
        BinaryCompatibilityGate.evaluate("clotho", RELEASE, breaks, entries.toList())

    private fun approved(identity: BreakIdentity, release: String = RELEASE) =
        ApprovedBreak(identity, "PR #9", release, "Accepted.")

    @Test
    fun `passes without breaks and without entries`() {
        assertTrue(evaluate(emptySet()).passed)
    }

    @Test
    fun `passes when every break matches exactly one entry for the release being built`() {
        val outcome = evaluate(setOf(METHOD, FIELD), approved(METHOD), approved(FIELD))

        assertTrue(outcome.passed)
        assertEquals(2, outcome.approved.size)
    }

    @Test
    fun `fails on a break without an entry`() {
        val outcome = evaluate(setOf(METHOD, FIELD), approved(METHOD))

        assertFalse(outcome.passed)
        assertEquals(listOf(FIELD), outcome.unapproved)
    }

    @Test
    fun `fails on an entry that no longer matches any break`() {
        val stale = approved(FIELD)
        val outcome = evaluate(setOf(METHOD), approved(METHOD), stale)

        assertFalse(outcome.passed)
        assertEquals(listOf(stale), outcome.staleEntries)
        assertTrue(outcome.unapproved.isEmpty())
    }

    @Test
    fun `fails on an entry for another release and does not treat its break as approved`() {
        val expired = approved(METHOD, release = "0.1.0-alpha04")
        val outcome = evaluate(setOf(METHOD), expired)

        assertFalse(outcome.passed)
        assertEquals(listOf(expired), outcome.wrongRelease)
        assertEquals(listOf(METHOD), outcome.unapproved)
    }

    @Test
    fun `fails when a break matches more than one entry`() {
        val outcome = evaluate(setOf(METHOD), approved(METHOD), approved(METHOD))

        assertFalse(outcome.passed)
        assertEquals(setOf(METHOD), outcome.ambiguous.keys)
        assertTrue(outcome.staleEntries.isEmpty())
    }

    @Test
    fun `an entry must match exactly, a different change type is not approved`() {
        val differentChange = approved(METHOD.copy(change = "METHOD_NOW_FINAL"))
        val outcome = evaluate(setOf(METHOD), differentChange)

        assertFalse(outcome.passed)
        assertEquals(listOf(METHOD), outcome.unapproved)
        assertEquals(listOf(differentChange), outcome.staleEntries)
    }

    @Test
    fun `an entry must match the member descriptor, not just its name`() {
        val otherOverload = approved(METHOD.copy(parameters = listOf("int")))
        val outcome = evaluate(setOf(METHOD), otherOverload)

        assertFalse(outcome.passed)
        assertEquals(listOf(METHOD), outcome.unapproved)
    }

    @Test
    fun `ignores entries that belong to another artifact`() {
        val outcome = evaluate(emptySet(), approved(METHOD.copy(artifact = "clotho-test")))

        assertTrue(outcome.passed)
    }

    @Test
    fun `manifest snippets round trip through the manifest parser`() {
        val identities =
            listOf(
                BreakIdentity("clotho", "org.example.Gone", MemberKind.CLASS, "", emptyList(), "", "CLASS_REMOVED"),
                BreakIdentity(
                    "clotho",
                    "org.example.Api",
                    MemberKind.SUPERCLASS,
                    "org.example.Base",
                    emptyList(),
                    "",
                    "SUPERCLASS_REMOVED",
                ),
                BreakIdentity(
                    "clotho-test",
                    "org.example.Api",
                    MemberKind.INTERFACE,
                    "java.io.Closeable",
                    emptyList(),
                    "",
                    "INTERFACE_REMOVED",
                ),
                BreakIdentity(
                    "clotho-android",
                    "org.example.Outer\$Inner",
                    MemberKind.CONSTRUCTOR,
                    "<init>",
                    listOf("int", "java.lang.String[]"),
                    "",
                    "CONSTRUCTOR_REMOVED",
                ),
                METHOD,
                FIELD,
            )

        for (identity in identities) {
            val xml = "<approvedBreaks>" + identity.manifestSnippet(RELEASE) + "</approvedBreaks>"
            val parsed = ApprovedBreakManifest.parse(xml.byteInputStream(), "snippet")

            assertEquals(listOf(identity), parsed.map { it.identity })
            assertEquals(RELEASE, parsed.single().release)
        }
    }

    @Test
    fun `a failing gate leaves the unfiltered report untouched and still writes its summary`(
        @TempDir dir: Path,
    ) {
        val report = write(dir, "clotho.xml", REPORT_WITH_METHOD_REMOVED)
        val manifest = write(dir, "approved-breaks.xml", "<approvedBreaks/>")
        val summary = dir.resolve("gate/clotho.md")
        val reportBefore = Files.readAllBytes(report)

        val error = assertThrows<GradleException> { verify(report, manifest, summary) }

        assertTrue(
            error.message!!.contains("unapproved break: clotho: org.example.Api void run(long) -> METHOD_REMOVED"),
            error.message,
        )
        assertArrayEquals(reportBefore, Files.readAllBytes(report))
        val text = Files.readString(summary)
        assertTrue(text.contains("Result: FAILED"), text)
        assertTrue(text.contains("name=\"run\""), "The summary must offer a manifest entry: $text")
    }

    @Test
    fun `a passing gate reports the approved break and leaves the report untouched`(@TempDir dir: Path) {
        val report = write(dir, "clotho.xml", REPORT_WITH_METHOD_REMOVED)
        val manifest =
            write(dir, "approved-breaks.xml", "<approvedBreaks>" + METHOD.manifestSnippet(RELEASE) + "</approvedBreaks>")
        val summary = dir.resolve("gate/clotho.md")
        val reportBefore = Files.readAllBytes(report)

        verify(report, manifest, summary)

        assertArrayEquals(reportBefore, Files.readAllBytes(report))
        val text = Files.readString(summary)
        assertTrue(text.contains("Result: PASSED"), text)
        assertTrue(text.contains("Approved breaks"), text)
    }

    @Test
    fun `a gate with a stale entry fails even though the comparison found nothing`(@TempDir dir: Path) {
        val report = write(dir, "clotho.xml", "<japicmp><classes/></japicmp>")
        val manifest =
            write(dir, "approved-breaks.xml", "<approvedBreaks>" + METHOD.manifestSnippet(RELEASE) + "</approvedBreaks>")

        val error = assertThrows<GradleException> { verify(report, manifest, dir.resolve("gate/clotho.md")) }

        assertTrue(error.message!!.contains("stale manifest entry"), error.message)
    }

    @Test
    fun `a missing manifest fails instead of being skipped`(@TempDir dir: Path) {
        val report = write(dir, "clotho.xml", "<japicmp><classes/></japicmp>")

        assertThrows<IOException> {
            verify(report, dir.resolve("does-not-exist.xml"), dir.resolve("gate/clotho.md"))
        }
    }

    private fun write(dir: Path, name: String, content: String): Path =
        Files.writeString(dir.resolve(name), content)

    private fun verify(report: Path, manifest: Path, summary: Path) =
        BinaryCompatibilityGate.verify(
            artifact = "clotho",
            targetRelease = RELEASE,
            report = report.toFile(),
            manifest = manifest.toFile(),
            summary = summary.toFile() as File,
        )

    private companion object {
        const val RELEASE = "0.1.0-alpha03"

        val METHOD =
            BreakIdentity(
                "clotho",
                "org.example.Api",
                MemberKind.METHOD,
                "run",
                listOf("long"),
                "void",
                "METHOD_REMOVED",
            )

        val FIELD =
            BreakIdentity(
                "clotho",
                "org.example.Api",
                MemberKind.FIELD,
                "LIMIT",
                emptyList(),
                "int",
                "FIELD_REMOVED",
            )

        val REPORT_WITH_METHOD_REMOVED =
            """
            <japicmp>
              <classes>
                <class fullyQualifiedName="org.example.Api" changeStatus="MODIFIED" binaryCompatible="false" sourceCompatible="false">
                  <methods>
                    <method name="run" changeStatus="REMOVED" binaryCompatible="false" sourceCompatible="false">
                      <parameters>
                        <parameter changeStatus="REMOVED" type="long"/>
                      </parameters>
                      <returnType changeStatus="REMOVED" oldValue="void" newValue="n.a."/>
                      <compatibilityChanges>
                        <compatibilityChange type="METHOD_REMOVED" binaryCompatible="false" sourceCompatible="false"/>
                      </compatibilityChanges>
                    </method>
                  </methods>
                </class>
              </classes>
            </japicmp>
            """.trimIndent()
    }
}
