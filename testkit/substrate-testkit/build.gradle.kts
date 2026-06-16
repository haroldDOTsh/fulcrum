plugins {
    `java-library`
}

dependencies {
    implementation(libs.testcontainers)
    implementation(libs.testcontainers.cassandra)
    implementation(libs.testcontainers.kafka)
    implementation(libs.testcontainers.postgresql)

    testImplementation(libs.testcontainers.junit.jupiter)
}
