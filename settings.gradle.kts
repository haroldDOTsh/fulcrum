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
    "core:content-resolver",
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
    "capability:capability-runtime",
    "control:allocation-bridge",
    "control:fault-controller",
    "control:lifecycle-controller",
    "control:queue-controller",
    "control:route-controller",
    "host:host-api",
    "host:paper-agent",
    "host:tick-runtime-api",
    "host:velocity-agent",
    "standard-capabilities:standard-contracts",
    "standard-capabilities:player-profile",
    "standard-capabilities:rank",
    "standard-capabilities:chat-decoration",
    "standard-capabilities:punishment",
    "distribution:profiles",
    "testkit:architecture-testkit",
    "testkit:substrate-testkit",
    "validation:architecture",
    "validation:standard-capabilities",
)

project(":data:contract-declarations").projectDir = file("data/contract-api")
