// Binary-compatibility validation with JApiCmp. Applying this plugin brings in the JApiCmp plugin;
// the shared baseline and task helpers live in BinaryCompatibility.kt and each published module
// registers its own comparison against the pinned `binaryCompatibilityBaseline` release.

plugins {
    id("me.champeau.gradle.japicmp")
}
