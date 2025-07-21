plugins {
    id("java-library")
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // Paper API for Bukkit/Paper types
    compileOnly("io.papermc.paper:paper-api:1.21.1-R0.1-SNAPSHOT")
    
    // Adventure API for modern text components
    implementation("net.kyori:adventure-api:4.17.0")
    implementation("net.kyori:adventure-text-minimessage:4.17.0")
    
    // message-api for menu messages
    implementation(project(":message-api"))
    
    // data-api for potential persistent menus
    implementation(project(":data-api"))
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

publishing {
    publications {
        named<MavenPublication>("maven") {
            artifactId = "menu-api"
        }
    }
}
