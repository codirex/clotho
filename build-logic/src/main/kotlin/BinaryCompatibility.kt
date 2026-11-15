package org.codirex.clotho.build

import java.io.File
import me.champeau.gradle.japicmp.JapicmpTask
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.file.FileCollection
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.register

private const val PUBLISHED_GROUP = "org.codirex.clotho"

/** One artifact of the published baseline release, resolved from Maven without its dependencies. */
class PublishedBaseline internal constructor(
    val module: String,
    val extension: String,
    internal val configuration: Configuration,
) {
    /**
     * The artifact as a plain file collection. The configuration itself is never handed to JApiCmp,
     * so the plugin cannot infer extra compared archives from its first-level dependencies.
     */
    val files: FileCollection
        get() = configuration.incoming.artifacts.artifactFiles

    /** The single resolved file. Resolves the configuration, so call it at execution time only. */
    val file: File
        get() = configuration.singleFile
}

private val Project.baselineVersion: String
    get() = providers.gradleProperty("binaryCompatibilityBaseline").get()

/**
 * Resolves [module] of the pinned baseline release (`binaryCompatibilityBaseline` in
 * gradle.properties) as a single [extension] artifact, without transitive dependencies.
 */
fun Project.publishedBaseline(module: String, extension: String = "jar"): PublishedBaseline {
    val version = baselineVersion

    // Gradle implicitly substitutes dependencies matching another project in this build. Temporarily
    // move this project's group while creating the external dependency so the published coordinates
    // cannot be replaced by the local project. The dependency itself keeps the real Maven
    // coordinates. This follows the workaround used by established JApiCmp Gradle builds.
    val originalGroup = group
    group = "$originalGroup.japicmp-baseline"
    try {
        val configuration =
            configurations
                .detachedConfiguration(
                    dependencies.create("$PUBLISHED_GROUP:$module:$version@$extension"),
                )
                .apply { isTransitive = false }
        return PublishedBaseline(module, extension, configuration)
    } finally {
        group = originalGroup
    }
}

/** Fails unless the baseline resolved to exactly the pinned Maven artifact, never a local file. */
private fun Project.requireMavenBaseline(baseline: PublishedBaseline) {
    val artifacts = baseline.configuration.incoming.artifacts.artifacts
    check(artifacts.size == 1) {
        "Expected exactly one published baseline artifact for ${baseline.module}, " +
            "found: ${artifacts.size}"
    }

    val artifact = artifacts.single()
    val component = artifact.id.componentIdentifier as? ModuleComponentIdentifier
    check(
        component != null &&
            component.group == PUBLISHED_GROUP &&
            component.module == baseline.module &&
            component.version == baselineVersion,
    ) {
        "Expected Maven baseline $PUBLISHED_GROUP:${baseline.module}:$baselineVersion, " +
            "but resolved ${artifact.id.componentIdentifier} from ${artifact.file}"
    }
    check(artifact.file.extension == baseline.extension) {
        "Expected a .${baseline.extension} baseline for ${baseline.module}, got ${artifact.file}"
    }
    check(!artifact.file.toPath().startsWith(projectDir.toPath())) {
        "Binary compatibility baseline unexpectedly points inside the project: ${artifact.file}"
    }

    logger.lifecycle("JApiCmp baseline: ${component.displayName} -> ${artifact.file.absolutePath}")
}

/**
 * Registers `validateBinaryCompatibilityTool`: compares [oldArchives] with [newArchives] using the
 * given dependency classpaths, reports under `build/reports/binary-compatibility/validation/` and
 * never fails on detected changes (this is validation of the tooling, not the enforced gate).
 *
 * Every [baselines] entry is checked to be the pinned Maven artifact before the comparison runs.
 */
fun Project.registerBinaryCompatibilityValidation(
    reportName: String,
    taskDescription: String,
    baselines: List<PublishedBaseline>,
    oldArchives: FileCollection,
    newArchives: FileCollection,
    oldClasspath: FileCollection = files(),
    newClasspath: FileCollection = files(),
): TaskProvider<JapicmpTask> {
    val reportDirectory = layout.buildDirectory.dir("reports/binary-compatibility/validation")
    return tasks.register<JapicmpTask>("validateBinaryCompatibilityTool") {
        group = "verification"
        description = taskDescription

        this.oldArchives.from(oldArchives)
        this.newArchives.from(newArchives)
        this.oldClasspath.from(oldClasspath)
        this.newClasspath.from(newClasspath)

        doFirst { baselines.forEach { project.requireMavenBaseline(it) } }

        accessModifier.set("protected")
        includeSynthetic.set(true)
        onlyModified.set(true)
        onlyBinaryIncompatibleModified.set(true)
        failOnModification.set(false)

        xmlOutputFile.set(reportDirectory.map { it.file("$reportName.xml") })
        mdOutputFile.set(reportDirectory.map { it.file("$reportName.md") })
    }
}
