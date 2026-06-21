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
        maven {
            name = "papermc"
            url = uri("https://repo.papermc.io/repository/maven-public/")
        }
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
    "data:store-memory",
    "data:store-kafka",
    "data:store-postgresql",
    "data:store-valkey",
    "data:subject-authority",
    "adapters:agones-allocator",
    "adapters:agones-fake",
    "adapters:object-storage",
    "capability:capability-api",
    "capability:capability-bundle-runtime",
    "capability:capability-runtime",
    "sdk:authoring-sdk",
    "sdk:authority-sdk",
    "control:allocation-bridge",
    "control:capability-backend-registration",
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
    "distribution:profiles",
    "distribution:service-launcher",
    "testkit:architecture-testkit",
    "testkit:substrate-testkit",
    "validation:architecture",
    "validation:auction-escrow-contract",
    "validation:auction-escrow-backend",
    "validation:auction-experience-bundle",
    "validation:authoring-sdk-conformance",
    "validation:authority-sdk-conformance",
    "validation:escrow-e2e",
    "validation:store-adapter-certification",
)

project(":data:contract-declarations").projectDir = file("data/contract-api")
