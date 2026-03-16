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

rootProject.name = "kubedroid"
include(":app")
include(":core:ui")
include(":core:network")
include(":core:security")
include(":core:database")
include(":feature:pods")
include(":feature:settings")
include(":feature:resources")
include(":feature:deployments")
include(":feature:nodes")
include(":feature:helm")
include(":feature:crd")
include(":feature:rbac")
include(":feature:metrics")
include(":feature:events")
include(":feature:storage")
include(":feature:network")
include(":feature:widget")
include(":feature:onboarding")
