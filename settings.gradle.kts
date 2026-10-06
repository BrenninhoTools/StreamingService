rootProject.name = "StreamingService"

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

include(":protocol")
include(":server")
include(":shared")
include(":androidApp")
include(":desktopApp")
include(":webApp")
