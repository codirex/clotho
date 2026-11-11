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
