rootProject.name = "fulcrum"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

include(
    "message-bus-api",
    "runtime",
    "runtime-velocity",
    "registry-service",
    "data-api"
)
