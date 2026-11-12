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
