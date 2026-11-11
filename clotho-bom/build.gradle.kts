plugins {
    `java-platform`
    id("clotho.publish")
}

dependencies {
    constraints {
        api("org.codirex.clotho:clotho:${project.version}")
        api("org.codirex.clotho:clotho-android:${project.version}")
        api("org.codirex.clotho:clotho-test:${project.version}")
    }
}

publishing {
    publications.withType<org.gradle.api.publish.maven.MavenPublication>().configureEach {
        pom.withXml {
            val root = asNode()
            val dependencyManagement =
                root.children()
                    .filterIsInstance<groovy.util.Node>()
                    .single { it.name() == "dependencyManagement" }
            val dependencies =
                dependencyManagement.children()
                    .filterIsInstance<groovy.util.Node>()
                    .single { it.name() == "dependencies" }
            val androidConstraint =
                dependencies.children()
                    .filterIsInstance<groovy.util.Node>()
                    .single { dependency ->
                        dependency.name() == "dependency" &&
                            dependency.children()
                                .filterIsInstance<groovy.util.Node>()
                                .any {
                                    it.name() == "artifactId" && it.text() == "clotho-android"
                                }
                    }

            if (
                androidConstraint.children()
                    .filterIsInstance<groovy.util.Node>()
                    .none { it.name() == "type" }
            ) {
                androidConstraint.appendNode("type", "aar")
            }
        }
    }
}
