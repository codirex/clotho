plugins {
    id("clotho.library")
}

dependencies {
    api(project(":clotho"))
}

tasks.jar {
    manifest {
        attributes("Automatic-Module-Name" to "org.codirex.clotho.test")
    }
}
