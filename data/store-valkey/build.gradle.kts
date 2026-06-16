plugins {
    `java-library`
}

dependencies {
    api(project(":data:authority-runtime"))
    implementation(libs.valkey.java)
}
