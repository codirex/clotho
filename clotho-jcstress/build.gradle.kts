// jcstress tests for the critical concurrent parts. They are not run by `build` or `check`
// (they take minutes and need real cores). Include this module with -PwithJcstress (see
// settings.gradle.kts) and run:  ./gradlew -PwithJcstress :clotho-jcstress:jcstress
plugins {
    `java-library`
    id("clotho.spotless")
}

dependencies {
    implementation(project(":clotho"))
    implementation(project(":clotho-test"))
    implementation(libs.jcstress.core)
    annotationProcessor(libs.jcstress.core)
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
    options.encoding = "UTF-8"
}

tasks.register<JavaExec>("jcstress") {
    group = "verification"
    description = "Runs the jcstress tests. Mode: -PjcstressMode=sanity|quick|default|tough (default quick)."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("org.openjdk.jcstress.Main")
    args("-m", providers.gradleProperty("jcstressMode").getOrElse("quick"))
}
