plugins {
    `java-library`
}

dependencies {
    api(project(":data:authority-core"))
    api(project(":data:route-contract"))
    implementation(project(":api:contract-api"))
    implementation(project(":api:kernel-api"))
}
