plugins {
    `java-library`
}

dependencies {
    api(project(":api:contract-api"))
    api(project(":api:kernel-api"))
    api(project(":capability:capability-api"))
    api(project(":data:authority-runtime"))
    api(project(":host:host-api"))
}
