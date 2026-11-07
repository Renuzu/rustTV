pluginManagement {
    repositories { google(); maven("https://maven-central.storage-download.googleapis.com/maven2/"); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); maven("https://maven-central.storage-download.googleapis.com/maven2/"); mavenCentral() }
}
rootProject.name = "RusTV"
include(":app")
