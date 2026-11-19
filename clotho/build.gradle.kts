import org.codirex.clotho.build.BinaryCompatibilityGate
import org.codirex.clotho.build.JApiCmpReport
import org.codirex.clotho.build.MemberKind
import org.codirex.clotho.build.publishedBaseline
import org.codirex.clotho.build.registerBinaryCompatibilityValidation

plugins {
    id("clotho.library")
    id("clotho.binary-compatibility")
}

tasks.jar {
    manifest {
        attributes("Automatic-Module-Name" to "org.codirex.clotho")
    }
}

// Binary compatibility: compare the published baseline with the current jar (see build-logic).
// `check` depends on the enforcing gate registered here.
val coreBaseline = publishedBaseline("clotho")

registerBinaryCompatibilityValidation(
    reportName = "clotho",
    taskDescription =
        "Validates JApiCmp against the published Clotho core baseline without enforcing compatibility.",
    baselines = listOf(coreBaseline),
    oldArchives = coreBaseline.files,
    newArchives = files(tasks.named("jar")),
)

// Self-contained fixture comparison: prove the configured JApiCmp version detects a removed
// public method, and that the enforcing gate rejects it (and accepts it once approved), without
// changing the library's real API.
val binaryCompatibilityOldFixtureCompile =
    tasks.register<JavaCompile>("compileBinaryCompatibilityOldFixture") {
        source(fileTree("src/japicmp-validation/old") { include("**/*.java") })
        classpath = files()
        destinationDirectory.set(layout.buildDirectory.dir("classes/japicmp-validation/old"))
        options.release.set(8)
        // The shared Java convention enables -Werror and source/target 8. JDK 21 warns that
        // those legacy flags are obsolete; suppress only that options warning for these fixtures.
        options.compilerArgs.add("-Xlint:-options")
    }

val binaryCompatibilityNewFixtureCompile =
    tasks.register<JavaCompile>("compileBinaryCompatibilityNewFixture") {
        source(fileTree("src/japicmp-validation/new") { include("**/*.java") })
        classpath = files()
        destinationDirectory.set(layout.buildDirectory.dir("classes/japicmp-validation/new"))
        options.release.set(8)
        options.compilerArgs.add("-Xlint:-options")
    }

val binaryCompatibilityOldFixtureJar =
    tasks.register<Jar>("binaryCompatibilityOldFixtureJar") {
        dependsOn(binaryCompatibilityOldFixtureCompile)
        archiveFileName.set("compatibility-fixture-old-1.0.jar")
        destinationDirectory.set(layout.buildDirectory.dir("binary-compatibility/fixtures"))
        from(binaryCompatibilityOldFixtureCompile.map { it.destinationDirectory })
    }

val binaryCompatibilityNewFixtureJar =
    tasks.register<Jar>("binaryCompatibilityNewFixtureJar") {
        dependsOn(binaryCompatibilityNewFixtureCompile)
        archiveFileName.set("compatibility-fixture-new-1.1.jar")
        destinationDirectory.set(layout.buildDirectory.dir("binary-compatibility/fixtures"))
        from(binaryCompatibilityNewFixtureCompile.map { it.destinationDirectory })
    }

val binaryCompatibilityFixtureReport =
    layout.buildDirectory.file("reports/binary-compatibility/validation/fixture.xml")

val compareBinaryCompatibilityFixtures =
    tasks.register<me.champeau.gradle.japicmp.JapicmpTask>("compareBinaryCompatibilityFixtures") {
        group = "verification"
        description = "Compares fixture jars containing a deliberate binary-incompatible API change."

        dependsOn(binaryCompatibilityOldFixtureJar, binaryCompatibilityNewFixtureJar)
        oldArchives.from(binaryCompatibilityOldFixtureJar)
        newArchives.from(binaryCompatibilityNewFixtureJar)

        accessModifier = "public"
        includeSynthetic = true
        onlyModified = true
        onlyBinaryIncompatibleModified = true
        failOnModification = false
        xmlOutputFile = binaryCompatibilityFixtureReport.get().asFile
    }

tasks.register("verifyBinaryCompatibilityToolDetectsBreakingChange") {
    group = "verification"
    description = "Asserts JApiCmp and the enforcing gate handle the deliberately removed fixture method."
    dependsOn(compareBinaryCompatibilityFixtures)

    doLast {
        val report = binaryCompatibilityFixtureReport.get().asFile
        check(report.isFile) {
            "JApiCmp did not produce the fixture comparison report: $report"
        }

        val xml = report.readText()
        check(xml.contains("removedMethod") && xml.contains("REMOVED", ignoreCase = true)) {
            "JApiCmp did not report the deliberately removed public method. Report: $report"
        }

        // The gate's own parser must understand a real JApiCmp report, not only hand-written ones.
        val breaks = JApiCmpReport.readBreaks("clotho", report)
        check(
            breaks.any {
                it.kind == MemberKind.METHOD &&
                    it.name == "removedMethod" &&
                    it.change == "METHOD_REMOVED"
            },
        ) {
            "The gate parser did not extract the removed fixture method from the real report. Parsed: $breaks"
        }

        // End to end on the real report: an unapproved break fails the gate, the same break passes
        // once an exactly matching entry exists for the release being built.
        val gateDirectory = layout.buildDirectory.dir("binary-compatibility/gate-fixture").get().asFile
        gateDirectory.mkdirs()
        val fixtureRelease = "0.0.0-fixture"

        val emptyManifest = File(gateDirectory, "empty-manifest.xml")
        emptyManifest.writeText("<approvedBreaks/>")
        val rejection =
            runCatching {
                    BinaryCompatibilityGate.verify(
                        "clotho",
                        fixtureRelease,
                        report,
                        emptyManifest,
                        File(gateDirectory, "rejected.md"),
                    )
                }
                .exceptionOrNull()
        check(rejection is GradleException) {
            "The gate must reject unapproved breaks from a real report, but got: $rejection"
        }

        val approvedManifest = File(gateDirectory, "approved-manifest.xml")
        approvedManifest.writeText(
            "<approvedBreaks>" +
                breaks.joinToString("") { it.manifestSnippet(fixtureRelease) } +
                "</approvedBreaks>",
        )
        BinaryCompatibilityGate.verify(
            "clotho",
            fixtureRelease,
            report,
            approvedManifest,
            File(gateDirectory, "approved.md"),
        )

        logger.lifecycle("JApiCmp negative-control passed: removedMethod was detected as REMOVED.")
        logger.lifecycle("Gate parser extracted: ${breaks.joinToString("; ")}")
        logger.lifecycle("Gate end-to-end: rejected without an entry, accepted with an exact entry.")
    }
}
