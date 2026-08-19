// Standalone dev-machine tool, deliberately NOT included in the root project's settings.gradle.kts
// - this runs region-package builds on a developer's machine, never as part of the Android app
// build, and needs its own dependencies (sqlite-jdbc, a plain JVM `application` plugin) that have
// no business being anywhere near the Android build's dependency graph.
rootProject.name = "region-build"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}
