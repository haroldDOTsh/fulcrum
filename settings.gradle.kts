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
    "player-core",
    "rank-api"
)
