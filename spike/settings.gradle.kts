// Phase 0 spike — a standalone Gradle build, deliberately outside the app's build path.
// Deleted at the end of Phase 0 (PLAN.md, Phase 0 task 2).
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
rootProject.name = "buy-my-way-spike"
include(":app")
