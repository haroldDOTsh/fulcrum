plugins {
    id("java-library")
}

repositories {
    mavenCentral()
}

dependencies {
    // Core dependencies
    compileOnly("com.fasterxml.jackson.core:jackson-databind:2.17.0")
    
    // Testing
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.2")
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