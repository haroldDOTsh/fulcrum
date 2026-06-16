plugins {
    `java-library`
}

dependencies {
    api(project(":host:host-api"))
    implementation(project(":data:route-contract"))
}
