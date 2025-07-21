rootProject.name = "fulcrum"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

include(
    "message-api",
    "data-api",
    "runtime",
    "rank-api",
    "menu-api"
)
