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
val binaryCompatibilityBaselineArtifact =
    configurations.create("binaryCompatibilityBaseline") {
        isCanBeConsumed = false
        isCanBeResolved = true
        isTransitive = false

        // This module is also produced by this build. Disable composite-build substitution so
        // Gradle downloads the published baseline instead of silently substituting :clotho.
        resolutionStrategy.useGlobalDependencySubstitutionRules.set(false)
    }

dependencies {
    add(
        binaryCompatibilityBaselineArtifact.name,
        "${project.group}:clotho:$binaryCompatibilityBaseline@jar",
    )
}

tasks.register<me.champeau.gradle.japicmp.JapicmpTask>("validateBinaryCompatibilityTool") {
    group = "verification"
    description =
        "Validates JApiCmp against the published Clotho core baseline without enforcing compatibility."

    dependsOn(tasks.named("jar"))

    oldArchives.from(binaryCompatibilityBaselineArtifact)
    newArchives.from(tasks.named("jar"))
    oldClasspath.from(binaryCompatibilityBaselineArtifact)
    newClasspath.from(sourceSets.main.get().output)

    accessModifier = "protected"
    includeSynthetic = true
    onlyModified = true
    onlyBinaryIncompatibleModified = true
    failOnModification = false

    val reportDirectory = layout.buildDirectory.dir("reports/binary-compatibility/validation")
    xmlOutputFile = reportDirectory.map { it.file("clotho.xml") }.get().asFile
    mdOutputFile = reportDirectory.map { it.file("clotho.md") }.get().asFile
}
