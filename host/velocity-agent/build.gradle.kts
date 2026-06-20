plugins {
    `java-library`
}

dependencies {
    api(project(":host:host-api"))
    api(project(":capability:capability-bundle-runtime"))
    implementation(project(":data:route-contract"))
    compileOnly(libs.velocity.api)
    annotationProcessor(libs.velocity.api)
    testImplementation(libs.velocity.api)
}

tasks.named<Jar>("jar") {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    dependsOn(configurations.runtimeClasspath)
    inputs.files(configurations.runtimeClasspath)
        .withPropertyName("pluginRuntimeClasspath")
    from({
        configurations.runtimeClasspath.get()
            .filter { it.name.endsWith(".jar") }
            .map { zipTree(it) }
    })
}
