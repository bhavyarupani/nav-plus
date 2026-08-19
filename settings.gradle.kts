pluginManagement {
    repositories {
        google { content { includeGroupByRegex("com\\.android.*"); includeGroupByRegex("com\\.google.*"); includeGroupByRegex("androidx.*") } }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "nav-plus"

include(":app")
include(":core:common")
include(":core:connectivity")
include(":core:map")
include(":core:routing")
include(":core:navigation")
include(":core:safety")
include(":core:search")
include(":core:regions")
include(":core:group")
include(":feature:home")
include(":feature:regions")
include(":feature:navigation")
include(":feature:search")
include(":feature:group")
