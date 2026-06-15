rootProject.name = "fulcrum"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

val creativeLibraryPath = providers.gradleProperty("creativeLibraryPath")
    .orElse(providers.environmentVariable("CREATIVE_LIBRARY_PATH"))
    .orElse("C:/dev/library")
    .get()
val creativeLibraryDir = file(creativeLibraryPath)
if (creativeLibraryDir.isDirectory) {
    includeBuild(creativeLibraryDir)
}

include(
    "message-bus-api",
    "data-valkey",
    "runtime",
    "runtime-velocity",
    "registry-service",
    "data-api"
)
