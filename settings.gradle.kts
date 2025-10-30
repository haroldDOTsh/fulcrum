rootProject.name = "fulcrum"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

include(
    "common-api",
    "common-api:message",
    "runtime",
    "runtime-velocity",
    "registry-service"
)
