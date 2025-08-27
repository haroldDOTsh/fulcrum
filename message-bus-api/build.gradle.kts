plugins {
    id("java-library")
}

repositories {
    mavenCentral()
}

dependencies {
    // Core dependencies
    compileOnly("com.fasterxml.jackson.core:jackson-databind:2.17.0")
    
    // Redis support
    compileOnly("io.lettuce:lettuce-core:6.3.0.RELEASE")
    
    // Logging
    compileOnly("org.slf4j:slf4j-api:2.0.9")
    
    // Testing
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.2")
    testImplementation("com.fasterxml.jackson.core:jackson-databind:2.17.0")
    testImplementation("io.lettuce:lettuce-core:6.3.0.RELEASE")
    testImplementation("org.slf4j:slf4j-simple:2.0.9")
}

tasks.test {
    useJUnitPlatform()
}

publishing {
    publications {
        named<MavenPublication>("maven") {
            artifactId = "message-bus-api"
        }
    }
}