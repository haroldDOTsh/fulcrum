plugins {
    `java-library`
}

dependencies {
    api(project(":sdk:authoring-sdk"))
    testImplementation(project(":adapters:object-storage"))
    testImplementation(project(":capability:capability-bundle-runtime"))
    testImplementation(project(":capability:capability-runtime"))
    testImplementation(project(":core:manifest-core"))
    testImplementation(project(":sdk:authority-sdk"))
}
