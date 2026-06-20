plugins {
    `java-library`
}

dependencies {
    api(project(":sdk:authority-sdk"))
    implementation(project(":capability:capability-runtime"))
}
