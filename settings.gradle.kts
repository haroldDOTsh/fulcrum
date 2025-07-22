rootProject.name = "fulcrum"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

include(
    "data-api",
    "module-api",
    "menu-api",
    "message-api",
    "rank-api",
    "runtime"
)
