plugins {
    `java-library`
}

dependencies {
    api(project(":sdk:authority-sdk"))
    testImplementation(project(":adapters:object-storage"))
    testImplementation(project(":capability:capability-bundle-runtime"))
    testImplementation(project(":control:capability-backend-registration"))
}
