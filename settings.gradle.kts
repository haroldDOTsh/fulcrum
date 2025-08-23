rootProject.name = "fulcrum"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

include(
    "data-api",
    "message-bus-api",
    "server-lifecycle-api",
    "runtime",
    "runtime-velocity",
    "registry-service"
)
