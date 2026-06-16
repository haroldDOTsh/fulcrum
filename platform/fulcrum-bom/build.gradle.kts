plugins {
    `java-platform`
}

javaPlatform {
    allowDependencies()
}

dependencies {
    constraints {
        api(libs.testcontainers)
        api(libs.testcontainers.cassandra)
        api(libs.testcontainers.junit.jupiter)
        api(libs.testcontainers.kafka)
        api(libs.testcontainers.postgresql)
    }
}
