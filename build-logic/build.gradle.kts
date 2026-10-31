plugins {
    `kotlin-dsl`
}

dependencies {
    implementation(libs.spotless.gradle.plugin)
    implementation(libs.animalsniffer.gradle.plugin)
    implementation(libs.maven.publish.gradle.plugin)
}
