rootProject.name = "fulcrum"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

include(
    "message-bus-api",
    "rank-api",
    "common-api",
    "runtime",
    "runtime-velocity",
    "registry-service",
    "data-api"
)
