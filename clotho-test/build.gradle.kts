plugins {
    id("clotho.library")
    alias(libs.plugins.japicmp)
}

dependencies {
    api(project(":clotho"))
}

tasks.jar {
    manifest {
        attributes("Automatic-Module-Name" to "org.codirex.clotho.test")
    }
}

// Validation spike only (see :clotho): compare the published clotho-test baseline with the current
// jar without enforcing compatibility. clotho-test exposes core types in its API, so the old side
// resolves them against the published core baseline and the new side against the current core jar.
val binaryCompatibilityBaseline = providers.gradleProperty("binaryCompatibilityBaseline").get()

// Same implicit project-substitution workaround as :clotho: the dependency keeps the real Maven
// coordinates, only the project's group is moved while the detached configuration is created.
fun publishedBaseline(module: String): org.gradle.api.artifacts.Configuration {
    val originalGroup = project.group
    project.group = "${project.group}.japicmp-baseline"
    try {
        return configurations.detachedConfiguration(
            dependencies.create("org.codirex.clotho:$module:$binaryCompatibilityBaseline@jar"),
        ).apply {
            isTransitive = false
        }
    } finally {
        project.group = originalGroup
    }
}

fun requireMavenBaseline(
    configuration: org.gradle.api.artifacts.Configuration,
    module: String,
) {
    val artifacts = configuration.incoming.artifacts.artifacts
    check(artifacts.size == 1) {
        "Expected exactly one published baseline artifact for $module, found: ${artifacts.size}"
    }

    val artifact = artifacts.single()
    val component =
        artifact.id.componentIdentifier as? org.gradle.api.artifacts.component.ModuleComponentIdentifier
    check(
        component != null &&
            component.group == "org.codirex.clotho" &&
            component.module == module &&
            component.version == binaryCompatibilityBaseline,
    ) {
        "Expected Maven baseline org.codirex.clotho:$module:$binaryCompatibilityBaseline, " +
            "but resolved ${artifact.id.componentIdentifier} from ${artifact.file}"
    }
    check(!artifact.file.toPath().startsWith(project.projectDir.toPath())) {
        "Binary compatibility baseline unexpectedly points inside the project: ${artifact.file}"
    }

    logger.lifecycle("JApiCmp baseline: ${component.displayName} -> ${artifact.file.absolutePath}")
}

val testBaselineArtifact = publishedBaseline("clotho-test")
val coreBaselineArtifact = publishedBaseline("clotho")

tasks.register<me.champeau.gradle.japicmp.JapicmpTask>("validateBinaryCompatibilityTool") {
    group = "verification"
    description =
        "Validates JApiCmp against the published Clotho test baseline without enforcing compatibility."

    dependsOn(tasks.named("jar"))

    // Compare only this artifact; the core jars are classpath only. Plain file collections are
    // passed (not the configurations themselves) so JApiCmp does not infer extra compared archives.
    oldArchives.from(testBaselineArtifact.incoming.artifacts.artifactFiles)
    newArchives.from(tasks.named("jar"))
    oldClasspath.from(coreBaselineArtifact.incoming.artifacts.artifactFiles)
    newClasspath.from(
        configurations.named("runtimeClasspath").map { it.incoming.artifacts.artifactFiles },
    )

    doFirst {
        requireMavenBaseline(testBaselineArtifact, "clotho-test")
        requireMavenBaseline(coreBaselineArtifact, "clotho")
    }

    accessModifier = "protected"
    includeSynthetic = true
    onlyModified = true
    onlyBinaryIncompatibleModified = true
    failOnModification = false

    val reportDirectory = layout.buildDirectory.dir("reports/binary-compatibility/validation")
    xmlOutputFile = reportDirectory.map { it.file("clotho-test.xml") }.get().asFile
    mdOutputFile = reportDirectory.map { it.file("clotho-test.md") }.get().asFile
}
