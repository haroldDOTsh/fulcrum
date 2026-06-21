plugins {
    `java-platform`
}

javaPlatform {
    allowDependencies()
}

dependencies {
    constraints {
        api(project(":api:kernel-api"))
        api(project(":api:contract-api"))
        api(project(":capability:capability-api"))
        api(project(":host:host-api"))
        api(project(":host:tick-runtime-api"))
        api(project(":sdk:authoring-sdk"))
        api(project(":sdk:authority-sdk"))
    }
}
