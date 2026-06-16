plugins {
    `java-library`
}

dependencies {
    testImplementation(project(":capability:capability-runtime"))
    testImplementation(project(":standard-capabilities:chat-decoration"))
    testImplementation(project(":standard-capabilities:party"))
    testImplementation(project(":standard-capabilities:friends"))
    testImplementation(project(":standard-capabilities:guild"))
    testImplementation(project(":standard-capabilities:player-profile"))
    testImplementation(project(":standard-capabilities:punishment"))
    testImplementation(project(":standard-capabilities:rank"))
    testImplementation(project(":standard-capabilities:standard-contracts"))
}
