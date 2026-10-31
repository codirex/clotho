plugins {
    id("clotho.library")
}

tasks.jar {
    manifest {
        attributes("Automatic-Module-Name" to "org.codirex.clotho")
    }
}
