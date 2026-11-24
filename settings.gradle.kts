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
rootProject.name = "BachatSamuhPranali"
include(":adminApp", ":agentApp", ":shared:finance-core", ":shared:data", ":shared:sync", ":shared:printing", ":shared:backup", ":shared:inventory-service")
