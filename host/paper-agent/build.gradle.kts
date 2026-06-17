plugins {
    `java-library`
}

dependencies {
    implementation(project(":core:artifact-layout"))
    implementation(project(":host:tick-runtime-api"))
    api(project(":host:host-api"))
    compileOnly(libs.paper.api)
}
