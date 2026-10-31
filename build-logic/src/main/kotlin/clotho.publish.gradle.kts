// Publishing to Maven Central (Central Portal) for every published module, Java or Android.
//
// Credentials and signing keys are never in the repository. They come from Gradle properties:
//   mavenCentralUsername, mavenCentralPassword            (Central Portal user token)
//   signing.keyId, signing.password, signing.secretKeyRingFile
// Locally they live in ~/.gradle/gradle.properties; in CI the release workflow passes them.
// Without signing properties the build still works (publishToMavenLocal is unsigned).
//
// POM texts come from gradle.properties (POM_*) and from each module's own gradle.properties
// (POM_NAME, POM_DESCRIPTION).

plugins {
    id("com.vanniktech.maven.publish")
}

mavenPublishing {
    publishToMavenCentral()

    val hasSigningKey =
        providers.gradleProperty("signing.keyId").isPresent ||
            providers.gradleProperty("signingInMemoryKey").isPresent
    if (hasSigningKey) {
        signAllPublications()
    }

    pom {
        name.set(providers.gradleProperty("POM_NAME"))
        description.set(providers.gradleProperty("POM_DESCRIPTION"))
        inceptionYear.set("2026")
        url.set(providers.gradleProperty("POM_URL"))
        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set(providers.gradleProperty("POM_DEVELOPER_ID"))
                name.set(providers.gradleProperty("POM_DEVELOPER_NAME"))
                url.set(providers.gradleProperty("POM_DEVELOPER_URL"))
            }
        }
        scm {
            url.set(providers.gradleProperty("POM_URL"))
            connection.set(providers.gradleProperty("POM_SCM_CONNECTION"))
            developerConnection.set(providers.gradleProperty("POM_SCM_DEV_CONNECTION"))
        }
        issueManagement {
            system.set("GitHub")
            url.set(providers.gradleProperty("POM_URL").map { "$it/issues" })
        }
    }
}
