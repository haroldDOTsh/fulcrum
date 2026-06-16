plugins {
    `java-library`
}

dependencies {
    testImplementation(project(":adapters:agones-allocator"))
    testImplementation(project(":api:contract-api"))
    testImplementation(project(":api:kernel-api"))
    testImplementation(project(":capability:capability-api"))
    testImplementation(project(":control:allocation-bridge"))
    testImplementation(project(":control:queue-controller"))
    testImplementation(project(":control:route-controller"))
    testImplementation(project(":core:content-resolver"))
    testImplementation(project(":core:manifest-core"))
    testImplementation(project(":core:session-runtime"))
    testImplementation(project(":data:authority-core"))
    testImplementation(project(":data:route-contract"))
    testImplementation(project(":data:session-authority"))
    testImplementation(project(":distribution:profiles"))
    testImplementation(project(":host:effect-admission"))
    testImplementation(project(":host:host-api"))
    testImplementation(project(":host:paper-agent"))
    testImplementation(project(":host:tick-runtime-api"))
    testImplementation(project(":host:velocity-agent"))
    testImplementation(project(":standard-capabilities:player-profile"))
    testImplementation(project(":standard-capabilities:punishment"))
    testImplementation(project(":standard-capabilities:rank"))
    testImplementation(project(":standard-capabilities:standard-contracts"))
}
