plugins {
    alias(libs.plugins.android.library)
    id("clotho.spotless")
    id("clotho.publish")
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
