// Standalone build so the pure-Kotlin client can be built and tested without the Android SDK.
// The Android app consumes it as an included build (see ../settings.gradle.kts).
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositories { mavenCentral() }
}
rootProject.name = "core"
