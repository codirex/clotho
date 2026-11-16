import org.codirex.clotho.build.publishedBaseline
import org.codirex.clotho.build.registerBinaryCompatibilityValidation
import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.type.ArtifactTypeDefinition

plugins {
    id("com.android.library")
    id("clotho.spotless")
    id("clotho.publish")
    id("clotho.binary-compatibility")
}

android {
    namespace = "org.codirex.clotho.android"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    api(project(":clotho"))
    // ClothoInitializer implements androidx.startup.Initializer, so the type is part of the API.
    api(libs.androidx.startup.runtime)
    // Only AndroidRealms needs lifecycle, and the app that calls it already has it (every
    // ComponentActivity and Fragment brings it). Apps that never use AndroidRealms never load it.
    compileOnly(libs.androidx.lifecycle.common)

    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.assertj.core)
    testImplementation(libs.androidx.lifecycle.runtime)

    // Instrumented tests (./gradlew :clotho-android:connectedDebugAndroidTest on a device or emulator).
    androidTestImplementation(libs.junit4)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.lifecycle.runtime)
}

// Binary compatibility validation (non-enforcing, see :clotho). The published AAR and the current
// release AAR are compared through the classes.jar each one contains.
val androidBaseline = publishedBaseline("clotho-android", extension = "aar")
val coreBaseline = publishedBaseline("clotho")

val extractBaselineClasses =
    tasks.register<Sync>("extractBinaryCompatibilityBaselineClasses") {
        from(provider { zipTree(androidBaseline.file) }) { include("classes.jar") }
        into(layout.buildDirectory.dir("binary-compatibility/baseline-aar"))
    }

val extractCurrentClasses =
    tasks.register<Sync>("extractBinaryCompatibilityCurrentClasses") {
        dependsOn("bundleReleaseAar")
        from(tasks.named<Zip>("bundleReleaseAar").flatMap { it.archiveFile }.map { zipTree(it) }) {
            include("classes.jar")
        }
        into(layout.buildDirectory.dir("binary-compatibility/current-aar"))
    }

// The classes.jar of every release compile-classpath dependency selected by [include]. JApiCmp needs
// them (the androidx types in the public API, compileOnly lifecycle) to resolve supertypes and
// signatures, so missing classes are resolved rather than ignored.
fun releaseCompileClasses(include: (ComponentIdentifier) -> Boolean): FileCollection =
    files(
        configurations.named("releaseCompileClasspath").map { configuration ->
            configuration.incoming
                .artifactView {
                    attributes.attribute(
                        ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE,
                        "android-classes-jar",
                    )
                    componentFilter { include(it) }
                }
                .files
        },
    )

val androidBootClasspath: FileCollection =
    files(androidComponents.sdkComponents.bootClasspath.map { jars -> jars.map { it.asFile } })

registerBinaryCompatibilityValidation(
    reportName = "clotho-android",
    taskDescription =
        "Validates JApiCmp against the published Clotho Android AAR without enforcing compatibility.",
    baselines = listOf(androidBaseline, coreBaseline),
    oldArchives =
        files(layout.buildDirectory.file("binary-compatibility/baseline-aar/classes.jar"))
            .builtBy(extractBaselineClasses),
    newArchives =
        files(layout.buildDirectory.file("binary-compatibility/current-aar/classes.jar"))
            .builtBy(extractCurrentClasses),
    oldClasspath =
        files(
            coreBaseline.files,
            releaseCompileClasses { it is ModuleComponentIdentifier },
            androidBootClasspath,
        ),
    newClasspath =
        files(
            releaseCompileClasses { it is ProjectComponentIdentifier },
            releaseCompileClasses { it is ModuleComponentIdentifier },
            androidBootClasspath,
        ),
)
