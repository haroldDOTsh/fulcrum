plugins {
    `java-library`
}

dependencies {
    api(project(":api:contract-api"))
    api(project(":api:kernel-api"))
    api(project(":core:artifact-layout"))
    api(project(":core:manifest-core"))
}
