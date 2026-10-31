// Rules for published library modules: Android API 24 guard, strict Javadoc, publishing.

plugins {
    id("clotho.java-base")
    id("ru.vyarus.animalsniffer")
    id("clotho.publish")
}

val libs = the<VersionCatalogsExtension>().named("libs")

dependencies {
    add(
        "signature",
        "net.sf.androidscents.signature:android-api-level-24:" +
            libs.findVersion("androidSignature").get().requiredVersion + "@signature",
    )
}

// Only production code is checked against the Android API 24 signature.
animalsniffer {
    sourceSets = listOf(the<SourceSetContainer>()["main"])
}

tasks.withType<Javadoc>().configureEach {
    (options as StandardJavadocDocletOptions).apply {
        encoding = "UTF-8"
        addStringOption("Xdoclint:all", "-quiet")
    }
}

// The publishing plugin attaches the sources and javadoc jars itself. Keep the strict javadoc
// (-Xdoclint:all) part of `check`, so a broken comment fails CI and not the release.
tasks.named("check") {
    dependsOn(tasks.withType<Javadoc>())
}
