plugins {
    `java-library`
}

dependencies {
    api(project(":data:authority-runtime"))
    api(libs.kafka.clients)
}
