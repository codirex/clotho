// Publishing to Maven Central (Central Portal) for every published module, Java or Android.
//
// Credentials and signing keys are never in the repository. They come from Gradle properties:
//   mavenCentralUsername, mavenCentralPassword            (Central Portal user token)
//   signing.keyId, signing.password, signing.secretKeyRingFile
// Locally they live in ~/.gradle/gradle.properties; in CI the release workflow passes them.
// Without signing properties the build still works (publishToMavenLocal is unsigned).
//
// POM texts come from gradle.properties (POM_*) and from each module's own gradle.properties
// (POM_NAME, POM_DESCRIPTION). A module's gradle.properties is only visible as a *project*
// property, so they are read with findProperty, not providers.gradleProperty.

plugins {
    id("com.vanniktech.maven.publish")
}

fun pom(key: String): Provider<String> = provider { findProperty(key)?.toString() }

mavenPublishing {
    publishToMavenCentral()

    val hasSigningKey =
        providers.gradleProperty("signing.keyId").isPresent ||
            providers.gradleProperty("signingInMemoryKey").isPresent
    if (hasSigningKey) {
        signAllPublications()
    }

    pom {
        name.set(pom("POM_NAME"))
        description.set(pom("POM_DESCRIPTION"))
        inceptionYear.set("2026")
        url.set(pom("POM_URL"))
        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set(pom("POM_DEVELOPER_ID"))
                name.set(pom("POM_DEVELOPER_NAME"))
                url.set(pom("POM_DEVELOPER_URL"))
            }
        }
        scm {
            url.set(pom("POM_URL"))
            connection.set(pom("POM_SCM_CONNECTION"))
            developerConnection.set(pom("POM_SCM_DEV_CONNECTION"))
        }
        issueManagement {
            system.set("GitHub")
            url.set(pom("POM_URL").map { "$it/issues" })
        }
    }
}