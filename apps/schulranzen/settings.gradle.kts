pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "Schulranzen"
include(":core", ":design", ":ink", ":app")
include(":feature:rechnen", ":feature:books", ":feature:study", ":feature:backup", ":feature:handwriting")
