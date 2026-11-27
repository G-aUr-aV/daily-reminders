pluginManagement {
    repositories { mavenCentral(); google(); gradlePluginPortal() }
    resolutionStrategy.eachPlugin {
        if (requested.id.id == "com.google.devtools.ksp") {
            useModule("com.google.devtools.ksp:symbol-processing-gradle-plugin:${requested.version}")
        }
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        maven(url = "https://maven-central.storage-download.googleapis.com/maven2") {
            content { includeGroup("org.robolectric") }
        }
        mavenCentral()
    }
}
rootProject.name = "DailyRhythm"
include(":app")
