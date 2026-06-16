plugins {
    `java-library`
}

dependencies {
    api(project(":api:contract-api"))
    api(project(":api:kernel-api"))
    api(project(":capability:capability-api"))
    api(project(":data:authority-core"))
    api(project(":standard-capabilities:standard-contracts"))

    testImplementation(project(":capability:capability-runtime"))
    testImplementation(project(":standard-capabilities:player-profile"))
}
