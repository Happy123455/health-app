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

rootProject.name = "MealTracker"
include(":app")       // phone app
include(":wear")      // Galaxy Watch smart alarm
include(":sleepcore") // shared: sensor features, wake model, schedule (plain Kotlin, unit-tested)
