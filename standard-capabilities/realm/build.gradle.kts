plugins {
    `java-library`
}

dependencies {
    api(project(":api:contract-api"))
    api(project(":capability:capability-api"))
    api(project(":core:artifact-layout"))
    api(project(":core:manifest-core"))
    api(project(":standard-capabilities:standard-contracts"))

    testImplementation(project(":capability:capability-runtime"))
}
