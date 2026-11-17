package org.codirex.clotho.build

import java.io.File
import org.gradle.api.GradleException

/** The result of matching the reported breaks of one artifact against the approved manifest. */
class GateOutcome(
    val approved: List<Pair<BreakIdentity, ApprovedBreak>>,
    /** Breaks without any approved entry for the current release. */
    val unapproved: List<BreakIdentity>,
    /** Breaks matched by more than one entry. */
    val ambiguous: Map<BreakIdentity, List<ApprovedBreak>>,
    /** Current-release entries that no reported break matches any more. */
    val staleEntries: List<ApprovedBreak>,
    /** Entries of this artifact that target another release than the one being built. */
    val wrongRelease: List<ApprovedBreak>,
) {
    val passed: Boolean
        get() =
            unapproved.isEmpty() &&
                ambiguous.isEmpty() &&
                staleEntries.isEmpty() &&
                wrongRelease.isEmpty()
}

/**
 * The enforcing step on top of the complete, unfiltered JApiCmp comparison.
 *
 * It never changes or filters the JApiCmp reports: it only reads them. It passes with reported
 * breaks only when every one matches exactly one manifest entry for the release being built, and
 * fails on any unmatched break, ambiguous match, wrong-release entry or stale entry.
 */
object BinaryCompatibilityGate {
    fun evaluate(
        artifact: String,
        targetRelease: String,
        breaks: Set<BreakIdentity>,
        manifest: List<ApprovedBreak>,
    ): GateOutcome {
        val mine = manifest.filter { it.identity.artifact == artifact }
        val (current, wrongRelease) = mine.partition { it.release == targetRelease }

        val approved = mutableListOf<Pair<BreakIdentity, ApprovedBreak>>()
        val unapproved = mutableListOf<BreakIdentity>()
        val ambiguous = linkedMapOf<BreakIdentity, List<ApprovedBreak>>()
        val used = mutableSetOf<ApprovedBreak>()
        for (reported in breaks.sortedBy { it.toString() }) {
            val candidates = current.filter { it.identity == reported }
            when (candidates.size) {
                0 -> unapproved += reported
                1 -> {
                    approved += reported to candidates.single()
                    used += candidates.single()
                }
                else -> {
                    ambiguous[reported] = candidates
                    used += candidates
                }
            }
        }
        val stale = current.filter { it !in used }
        return GateOutcome(approved, unapproved, ambiguous, stale, wrongRelease)
    }

    /**
     * Reads the JApiCmp XML [report] and the [manifest], writes a Markdown [summary] (also when the
     * gate fails) and throws a [GradleException] unless the outcome passes.
     */
    fun verify(artifact: String, targetRelease: String, report: File, manifest: File, summary: File) {
        val breaks = JApiCmpReport.readBreaks(artifact, report)
        val entries = ApprovedBreakManifest.read(manifest)
        val outcome = evaluate(artifact, targetRelease, breaks, entries)

        summary.parentFile.mkdirs()
        summary.writeText(render(artifact, targetRelease, breaks.size, outcome))

        if (!outcome.passed) {
            throw GradleException(failureMessage(artifact, targetRelease, outcome, summary))
        }
    }

    private fun render(
        artifact: String,
        targetRelease: String,
        reported: Int,
        outcome: GateOutcome,
    ): String = buildString {
        appendLine("# Binary compatibility gate: $artifact")
        appendLine()
        appendLine("- Release being built: $targetRelease")
        appendLine("- Reported binary incompatibilities: $reported")
        appendLine("- Result: ${if (outcome.passed) "PASSED" else "FAILED"}")

        section(
            "Approved breaks",
            outcome.approved.map { (reportedBreak, entry) ->
                "$reportedBreak (${entry.approval}: ${entry.rationale})"
            },
        )
        section("Unapproved breaks", outcome.unapproved.map { it.toString() })
        section(
            "Ambiguous matches",
            outcome.ambiguous.map { (reportedBreak, entries) -> "$reportedBreak matches ${entries.size} entries" },
        )
        section("Stale manifest entries", outcome.staleEntries.map { it.identity.toString() })
        section(
            "Entries for another release",
            outcome.wrongRelease.map { "${it.identity} targets ${it.release}" },
        )

        if (outcome.unapproved.isNotEmpty()) {
            appendLine()
            appendLine("## Manifest entries to review")
            appendLine()
            appendLine("Add these to `binary-compatibility/approved-breaks.xml` only after the break is approved.")
            appendLine()
            appendLine("```xml")
            outcome.unapproved.forEach { appendLine(it.manifestSnippet(targetRelease)) }
            appendLine("```")
        }
    }

    private fun StringBuilder.section(title: String, lines: List<String>) {
        if (lines.isEmpty()) return
        appendLine()
        appendLine("## $title")
        appendLine()
        lines.forEach { appendLine("- $it") }
    }

    private fun failureMessage(
        artifact: String,
        targetRelease: String,
        outcome: GateOutcome,
        summary: File,
    ): String = buildString {
        appendLine("Binary compatibility gate failed for $artifact:")
        outcome.unapproved.forEach { appendLine("  - unapproved break: $it") }
        outcome.ambiguous.forEach { (reportedBreak, entries) ->
            appendLine("  - ambiguous: $reportedBreak matches ${entries.size} manifest entries")
        }
        outcome.staleEntries.forEach {
            appendLine("  - stale manifest entry, no reported break matches it: ${it.identity}")
        }
        outcome.wrongRelease.forEach {
            appendLine(
                "  - manifest entry targets ${it.release}, but $targetRelease is being built: ${it.identity}",
            )
        }
        append("Summary with manifest entries to review: ${summary.path}. ")
        append("The unfiltered JApiCmp reports are kept under build/reports/binary-compatibility/validation/.")
    }
}
