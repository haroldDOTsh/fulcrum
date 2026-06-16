plugins {
    `java-library`
}

dependencies {
    implementation(project(":core:artifact-layout"))
    api(project(":host:host-api"))
}
