plugins {
    `java-library`
}

dependencies {
    api(project(":host:host-api"))
    implementation(project(":data:route-contract"))
    compileOnly(libs.velocity.api)
    annotationProcessor(libs.velocity.api)
    testImplementation(libs.velocity.api)
}
