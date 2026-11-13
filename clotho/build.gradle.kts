plugins {
    id("clotho.library")
    alias(libs.plugins.japicmp)
}

tasks.jar {
    manifest {
        attributes("Automatic-Module-Name" to "org.codirex.clotho")
    }
}

// Validation spike only: prove JApiCmp 0.4.6 resolves the published baseline and runs on the
// repository's Gradle wrapper before wiring any compatibility check into the required CI path.
val binaryCompatibilityBaseline = providers.gradleProperty("binaryCompatibilityBaseline").get()

// Gradle implicitly substitutes dependencies matching another project in this build. Temporarily
// move this project's group while creating the external dependency so the published coordinates
// cannot be replaced by the local :clotho project. The dependency itself keeps the real Maven
// coordinates. This follows the workaround used by established JApiCmp Gradle builds.
val binaryCompatibilityBaselineArtifact = run {
    val originalGroup = project.group
    project.group = "${project.group}.japicmp-baseline"
    try {
        configurations.detachedConfiguration(
            dependencies.create(
                "org.codirex.clotho:clotho:$binaryCompatibilityBaseline@jar",
            ),
        ).apply {
            isTransitive = false
        }
    } finally {
        project.group = originalGroup
    }
}

tasks.register<me.champeau.gradle.japicmp.JapicmpTask>("validateBinaryCompatibilityTool") {
    group = "verification"
    description =
        "Validates JApiCmp against the published Clotho core baseline without enforcing compatibility."

    dependsOn(tasks.named("jar"))

    // For configuration-backed classpaths, JApiCmp infers the compared archives from the
    // first-level dependencies. Keep the published baseline and current jar as separate inputs.
    oldClasspath.from(binaryCompatibilityBaselineArtifact)
    newClasspath.from(tasks.named("jar"))

    doFirst {
        val artifacts = binaryCompatibilityBaselineArtifact.incoming.artifacts.artifacts
        check(artifacts.size == 1) {
            "Expected exactly one published Clotho baseline artifact, found: ${artifacts.size}"
        }

        val artifact = artifacts.single()
        val component =
            artifact.id.componentIdentifier as? org.gradle.api.artifacts.component.ModuleComponentIdentifier
        check(
            component != null &&
                component.group == "org.codirex.clotho" &&
                component.module == "clotho" &&
                component.version == binaryCompatibilityBaseline,
        ) {
            "Expected Maven baseline org.codirex.clotho:clotho:$binaryCompatibilityBaseline, " +
                "but resolved ${artifact.id.componentIdentifier} from ${artifact.file}"
        }
        check(!artifact.file.toPath().startsWith(project.projectDir.toPath())) {
            "Binary compatibility baseline unexpectedly points inside the project: ${artifact.file}"
        }

        logger.lifecycle(
            "JApiCmp baseline: ${component.displayName} -> ${artifact.file.absolutePath}",
        )
    }

    accessModifier = "protected"
    includeSynthetic = true
    onlyModified = true
    onlyBinaryIncompatibleModified = true
    failOnModification = false

    val reportDirectory = layout.buildDirectory.dir("reports/binary-compatibility/validation")
    xmlOutputFile = reportDirectory.map { it.file("clotho.xml") }.get().asFile
    mdOutputFile = reportDirectory.map { it.file("clotho.md") }.get().asFile
}


// Self-contained fixture comparison: prove the configured JApiCmp version detects a removed
// public method without changing the library's real API or making the production check enforcing.
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
    description = "Asserts JApiCmp detects the deliberately removed fixture method."
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

        logger.lifecycle("JApiCmp negative-control passed: removedMethod was detected as REMOVED.")
    }
}
