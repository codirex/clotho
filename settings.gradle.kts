pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
        google()
    }
}

rootProject.name = "clotho-framework"

include(":app")
include(":clotho")
include(":clotho-android")
include(":clotho-test")

if (providers.gradleProperty("withJcstress").isPresent) {
    include(":clotho-jcstress")
}

if (providers.gradleProperty("withBench").isPresent) {
    include(":clotho-bench")
}
