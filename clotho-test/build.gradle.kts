import org.codirex.clotho.build.publishedBaseline
import org.codirex.clotho.build.registerBinaryCompatibilityValidation

plugins {
    id("clotho.library")
    id("clotho.binary-compatibility")
}

dependencies {
    api(project(":clotho"))
}

tasks.jar {
    manifest {
        attributes("Automatic-Module-Name" to "org.codirex.clotho.test")
    }
}

// Validation only (see :clotho). clotho-test exposes core types in its API, so the old side resolves
// them against the published core baseline and the new side against the current core jar.
val testBaseline = publishedBaseline("clotho-test")
val coreBaseline = publishedBaseline("clotho")

registerBinaryCompatibilityValidation(
    reportName = "clotho-test",
    taskDescription =
        "Validates JApiCmp against the published Clotho test baseline without enforcing compatibility.",
    baselines = listOf(testBaseline, coreBaseline),
    oldArchives = testBaseline.files,
    newArchives = files(tasks.named("jar")),
    oldClasspath = coreBaseline.files,
    newClasspath =
        files(configurations.named("runtimeClasspath").map { it.incoming.artifacts.artifactFiles }),
)
