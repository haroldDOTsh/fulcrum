plugins {
    `java-library`
}

dependencies {
    testImplementation(project(":adapters:agones-fake"))
    testImplementation(project(":api:contract-api"))
    testImplementation(project(":api:kernel-api"))
    testImplementation(project(":control:route-controller"))
    testImplementation(project(":data:authority-core"))
    testImplementation(project(":host:host-api"))
    testImplementation(project(":standard-capabilities:rank"))
}
