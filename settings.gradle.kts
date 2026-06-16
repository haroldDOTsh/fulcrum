pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "fulcrum"

include(
    "platform:fulcrum-bom",
    "api:kernel-api",
    "api:contract-api",
    "core:manifest-core",
    "core:session-runtime",
    "data:contract-declarations",
    "data:contract-codegen",
    "data:authority-core",
    "data:artifact-authority",
    "data:presence-authority",
    "data:route-contract",
    "data:route-authority",
    "data:session-authority",
    "data:subject-authority",
    "adapters:agones-allocator",
    "adapters:agones-fake",
    "capability:capability-api",
    "control:allocation-bridge",
    "control:fault-controller",
    "control:queue-controller",
    "control:route-controller",
    "host:host-api",
    "host:paper-agent",
    "host:tick-runtime-api",
    "host:velocity-agent",
    "distribution:profiles",
    "testkit:architecture-testkit",
    "testkit:substrate-testkit",
    "validation:architecture",
)

project(":data:contract-declarations").projectDir = file("data/contract-api")
