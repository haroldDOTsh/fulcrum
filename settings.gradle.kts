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
    "core:artifact-layout",
    "core:content-resolver",
    "core:session-runtime",
    "data:contract-declarations",
    "data:contract-codegen",
    "data:authority-core",
    "data:authority-runtime",
    "data:artifact-authority",
    "data:presence-authority",
    "data:route-contract",
    "data:route-authority",
    "data:session-authority",
    "data:store-cassandra",
    "data:store-kafka",
    "data:store-postgresql",
    "data:store-valkey",
    "data:subject-authority",
    "adapters:agones-allocator",
    "adapters:agones-fake",
    "capability:capability-api",
    "capability:capability-runtime",
    "control:allocation-bridge",
    "control:capability-enablement-controller",
    "control:fault-controller",
    "control:instance-registry-controller",
    "control:lifecycle-controller",
    "control:queue-controller",
    "control:route-controller",
    "host:effect-admission",
    "host:host-api",
    "host:paper-agent",
    "host:tick-runtime-api",
    "host:velocity-agent",
    "host:worker-agent",
    "standard-capabilities:standard-contracts",
    "standard-capabilities:player-profile",
    "standard-capabilities:rank",
    "standard-capabilities:chat-decoration",
    "standard-capabilities:punishment",
    "standard-capabilities:party",
    "standard-capabilities:friends",
    "standard-capabilities:guild",
    "standard-capabilities:economy",
    "standard-capabilities:stats",
    "standard-capabilities:auction",
    "standard-capabilities:realm",
    "distribution:profiles",
    "distribution:service-launcher",
    "testkit:architecture-testkit",
    "testkit:substrate-testkit",
    "validation:architecture",
    "validation:fleet-e2e",
    "validation:store-adapter-certification",
    "validation:synthetic-load",
    "validation:standard-capabilities",
)

project(":data:contract-declarations").projectDir = file("data/contract-api")
