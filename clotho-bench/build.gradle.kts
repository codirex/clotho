// JMH benchmarks: Clotho against java.util.concurrent. Not part of `build` or `check`
// (they take many minutes and want a quiet machine). Include the module with -PwithBench:
//
//   ./gradlew -PwithBench :clotho-bench:bench                       # full run (long)
//   ./gradlew -PwithBench :clotho-bench:bench -PbenchQuick          # smoke run (~2 min)
//   ./gradlew -PwithBench :clotho-bench:bench -PbenchFilter=Timer   # one benchmark class
//
// Output: build/results/jmh/results.json (+ scripts/jmh_to_md.py turns it into a table).
plugins {
    `java-library`
    id("clotho.spotless")
}

dependencies {
    implementation(project(":clotho"))
    implementation(libs.jmh.core)
    annotationProcessor(libs.jmh.generator)
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
    options.encoding = "UTF-8"
}

tasks.register<JavaExec>("bench") {
    group = "verification"
    description = "Runs the JMH suite. -PbenchQuick for a short run, -PbenchFilter=<regex> to select."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("org.openjdk.jmh.Main")

    val quick = providers.gradleProperty("benchQuick").isPresent
    val filter = providers.gradleProperty("benchFilter").getOrElse(".*")
    val out = layout.buildDirectory.file("results/jmh/results.json").get().asFile

    doFirst { out.parentFile.mkdirs() }

    val timing =
        if (quick) {
            listOf("-f", "1", "-wi", "1", "-w", "1s", "-i", "2", "-r", "1s")
        } else {
            listOf("-f", "3", "-wi", "5", "-w", "2s", "-i", "10", "-r", "2s")
        }
    // -prof gc adds allocation per operation (gc.alloc.rate.norm), which is part of the comparison.
    args(listOf(filter, "-rf", "json", "-rff", out.absolutePath, "-prof", "gc") + timing)
}
