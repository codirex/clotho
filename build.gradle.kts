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
