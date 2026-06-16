plugins {
    application
}

application {
    applicationName = "fulcrum"
    mainClass.set("sh.harold.fulcrum.distribution.launcher.FulcrumLauncher")
}

dependencies {
    runtimeOnly(project(":adapters:agones-allocator"))
    runtimeOnly(project(":adapters:agones-fake"))
    runtimeOnly(project(":capability:capability-runtime"))
    runtimeOnly(project(":control:allocation-bridge"))
    runtimeOnly(project(":control:capability-enablement-controller"))
    runtimeOnly(project(":control:fault-controller"))
    runtimeOnly(project(":control:instance-registry-controller"))
    runtimeOnly(project(":control:lifecycle-controller"))
    runtimeOnly(project(":control:queue-controller"))
    runtimeOnly(project(":control:route-controller"))
    runtimeOnly(project(":data:artifact-authority"))
    runtimeOnly(project(":data:authority-runtime"))
    runtimeOnly(project(":data:presence-authority"))
    runtimeOnly(project(":data:route-authority"))
    runtimeOnly(project(":data:session-authority"))
    runtimeOnly(project(":data:subject-authority"))
    runtimeOnly(project(":distribution:profiles"))
    runtimeOnly(project(":host:effect-admission"))
    runtimeOnly(project(":host:paper-agent"))
    runtimeOnly(project(":host:tick-runtime-api"))
    runtimeOnly(project(":host:velocity-agent"))
    runtimeOnly(project(":host:worker-agent"))
    runtimeOnly(project(":standard-capabilities:chat-decoration"))
    runtimeOnly(project(":standard-capabilities:player-profile"))
    runtimeOnly(project(":standard-capabilities:punishment"))
    runtimeOnly(project(":standard-capabilities:rank"))
    runtimeOnly(project(":standard-capabilities:realm"))
    runtimeOnly(project(":standard-capabilities:standard-contracts"))
}

tasks.named("check") {
    dependsOn(tasks.named("installDist"))
}
