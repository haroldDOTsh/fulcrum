plugins {
    `java-platform`
}

javaPlatform {
    allowDependencies()
}

dependencies {
    constraints {
        api(project(":sdk:authoring-sdk"))
        api(project(":sdk:authority-sdk"))
        api(libs.kafka.clients)
        api(libs.paper.api)
        api(libs.testcontainers)
        api(libs.testcontainers.cassandra)
        api(libs.testcontainers.junit.jupiter)
        api(libs.testcontainers.kafka)
        api(libs.testcontainers.postgresql)
        api(libs.velocity.api)
    }
}
