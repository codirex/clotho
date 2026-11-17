plugins {
  alias(libs.plugins.maven.publish) apply false
  alias(libs.plugins.android.library) apply false
  alias(libs.plugins.android.application) apply false
}

val clothoGroup = providers.gradleProperty("GROUP").get()
val clothoVersion = providers.gradleProperty("VERSION_NAME").get()

allprojects {
    group = clothoGroup
    version = clothoVersion
}

// Runs the approved-break gate for every published code artifact. Each module's `check` already
// depends on its own gate; this aggregate also covers :clotho-android, whose CI job does not run
// `check`.
tasks.register("checkBinaryCompatibility") {
    group = "verification"
    description = "Enforces the approved-break manifest for every published code artifact."
    dependsOn(
        ":clotho:verifyBinaryCompatibility",
        ":clotho-test:verifyBinaryCompatibility",
        ":clotho-android:verifyBinaryCompatibility",
    )
}
