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

rootProject.name = "light-sdk"

includeBuild("plugin")

include(":lp3keyboard-ui")

include(":lint-rules")
include(":sdk:shared")
include(":sdk:ui")
include(":sdk:client")
include(":sdk:server")
include(":sdk:emulator")
include(":tool")
include(":examples:ui-demo")
project(":examples:ui-demo").projectDir = file("examples/ui-demo")
include(":examples:weather")
project(":examples:weather").projectDir = file("examples/weather")
