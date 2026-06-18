plugins {
    `java-library`
}

dependencies {
    testImplementation(project(":adapters:object-storage"))
    testImplementation(project(":api:contract-api"))
    testImplementation(project(":api:kernel-api"))
    testImplementation(project(":core:artifact-layout"))
    testImplementation(project(":core:manifest-core"))
    testImplementation(project(":data:authority-core"))
    testImplementation(project(":data:authority-runtime"))
    testImplementation(project(":data:store-cassandra"))
    testImplementation(project(":data:store-kafka"))
    testImplementation(project(":data:store-postgresql"))
    testImplementation(project(":data:store-valkey"))
    testImplementation(project(":testkit:substrate-testkit"))
    testImplementation(libs.cassandra.driver.core)
    testImplementation(libs.kafka.clients)
    testImplementation(libs.postgresql)
    testImplementation(libs.testcontainers)
    testImplementation(libs.valkey.java)
}
