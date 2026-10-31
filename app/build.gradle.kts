plugins {
    alias(libs.plugins.android.application)
    id("clotho.spotless")
}

android {
    namespace = "org.codirex.clotho.sample"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "org.codirex.clotho.sample"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // brings :clotho (api) and installs the main lane through androidx.startup
    implementation(project(":clotho-android"))
}
