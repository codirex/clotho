import com.diffplug.gradle.spotless.SpotlessExtension

// Formatting and license headers. Safe to apply to both Java and Android modules.

plugins {
    id("com.diffplug.spotless")
}

val libs = the<VersionCatalogsExtension>().named("libs")

extensions.configure<SpotlessExtension> {
    java {
        target("src/**/*.java")
        googleJavaFormat(libs.findVersion("googleJavaFormat").get().requiredVersion)
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
        licenseHeaderFile(rootProject.file("config/license-header.txt"))
    }
}
