plugins {
    `java-library`
}

dependencies {
    api(project(":capability:capability-bundle-runtime"))
    implementation(project(":core:artifact-layout"))
    implementation(project(":host:tick-runtime-api"))
    api(project(":host:host-api"))
    compileOnly(libs.paper.api)
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
