pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "ETroute"
include(":android")
include(":device-validator")
// Opt-in feasibility APK only; the default application/build does not include a Python runtime.
if (providers.gradleProperty("etrouteRuntimePack").orNull == "true") {
    include(":runtimepack-validator")
}
