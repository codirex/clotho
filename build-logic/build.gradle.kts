plugins {
    `kotlin-dsl`
}

dependencies {
    implementation(libs.spotless.gradle.plugin)
    implementation(libs.animalsniffer.gradle.plugin)
    implementation(libs.maven.publish.gradle.plugin)
    implementation(libs.japicmp.gradle.plugin)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
