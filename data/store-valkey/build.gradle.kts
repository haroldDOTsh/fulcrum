plugins {
    `java-library`
}

dependencies {
    api(project(":data:authority-runtime"))
    api(libs.valkey.java)
}
