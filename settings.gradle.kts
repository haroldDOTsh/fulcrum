rootProject.name = "fulcrum"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

include(
    "common-api",
    "runtime",
    "runtime-velocity",
    "registry-service"
)
