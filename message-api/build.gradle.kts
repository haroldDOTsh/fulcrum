plugins {
    id("java-library")
}

dependencies {
    // MiniMessage for color parsing
    implementation("net.kyori:adventure-api:4.17.0")
    implementation("net.kyori:adventure-text-minimessage:4.17.0")
}

publishing {
    publications {
        named<MavenPublication>("maven") {
            artifactId = "message-api"
        }
    }
}
