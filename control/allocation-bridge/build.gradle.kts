plugins {
    `java-library`
}

dependencies {
    api(project(":api:contract-api"))
    api(project(":api:kernel-api"))
    api(project(":control:queue-controller"))
    api(project(":host:host-api"))
}
