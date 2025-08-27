plugins {
    java
    `maven-publish`
}

group = "sh.harold.fulcrum"
version = "1.0.0-SNAPSHOT"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.velocitypowered.com/snapshots/")
}

dependencies {
    // Core dependencies
    implementation("io.lettuce:lettuce-core:6.2.4.RELEASE")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.15.2")
    
    // Optional: Server lifecycle API for ServerIdentifier
    compileOnly(project(":server-lifecycle-api"))
    
    // Platform APIs (compile only)
    compileOnly("io.papermc.paper:paper-api:1.20.4-R0.1-SNAPSHOT")
    compileOnly("com.velocitypowered:velocity-api:3.3.0-SNAPSHOT")
    
    // Testing
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.0")
    testImplementation("org.mockito:mockito-core:5.4.0")
    testImplementation("org.testcontainers:testcontainers:1.19.0")
    testImplementation("org.testcontainers:junit-jupiter:1.19.0")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.test {
    useJUnitPlatform()
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}