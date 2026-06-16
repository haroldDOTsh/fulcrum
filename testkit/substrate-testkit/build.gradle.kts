plugins {
    `java-library`
}

dependencies {
    implementation(libs.testcontainers)
    implementation(libs.testcontainers.cassandra)
    implementation(libs.testcontainers.kafka)
    implementation(libs.testcontainers.postgresql)

    testImplementation(project(":data:contract-codegen"))
    testImplementation(libs.kafka.clients)
    testImplementation(libs.testcontainers.junit.jupiter)
}
