plugins {
    `java-library`
}

dependencies {
    api(project(":capability:capability-api"))
    api(project(":standard-capabilities:standard-contracts"))

    testImplementation(project(":capability:capability-runtime"))
    testImplementation(project(":standard-capabilities:player-profile"))
}
