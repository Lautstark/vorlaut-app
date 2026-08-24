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

rootProject.name = "vorlaut-app"

// :boardpackage is deliberately a plain JVM module, not an Android library. The
// parser is the part that has to be right, and keeping it free of the Android
// SDK is what lets it be tested on the JVM in milliseconds against the exchange
// fixtures. Nothing in it may import android.*.
include(":boardpackage")
include(":app")
