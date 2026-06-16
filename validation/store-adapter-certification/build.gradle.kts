plugins {
    `java-library`
}

dependencies {
    testImplementation(project(":adapters:object-storage"))
    testImplementation(project(":api:kernel-api"))
    testImplementation(project(":core:artifact-layout"))
    testImplementation(project(":core:manifest-core"))
}
