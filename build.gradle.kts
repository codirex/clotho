// Root project: shared coordinates only. Behaviour lives in build-logic convention plugins.
val clothoGroup = providers.gradleProperty("GROUP").get()
val clothoVersion = providers.gradleProperty("VERSION_NAME").get()

allprojects {
    group = clothoGroup
    version = clothoVersion
}
