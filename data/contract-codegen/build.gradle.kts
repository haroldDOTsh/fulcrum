plugins {
    `java-library`
}

dependencies {
    api(project(":data:contract-declarations"))
    implementation(project(":api:contract-api"))
}
