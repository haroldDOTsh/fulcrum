plugins {
    id("java")
    id("java-library")
}

group = "sh.harold.fulcrum"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    // MongoDB Driver
    implementation("org.mongodb:mongodb-driver-sync:4.11.1")
    // Jackson for shared session serialization
    implementation("com.fasterxml.jackson.core:jackson-databind:2.15.3")
    implementation("com.fasterxml.jackson.core:jackson-annotations:2.15.3")
    implementation("com.fasterxml.jackson.core:jackson-core:2.15.3")

    // PostgreSQL Driver
    implementation("org.postgresql:postgresql:42.7.1")
    
    // HikariCP Connection Pooling
    implementation("com.zaxxer:HikariCP:5.1.0")
    
    // JSON Processing
    implementation("com.google.code.gson:gson:2.10.1")
    
    // Logging
    implementation("org.slf4j:slf4j-api:2.0.9")
    implementation("org.slf4j:slf4j-simple:2.0.9")
    
    // Testing Dependencies
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")
    testImplementation("org.junit.jupiter:junit-jupiter-params:5.10.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    
    // Mocking
    testImplementation("org.mockito:mockito-core:5.7.0")
    testImplementation("org.mockito:mockito-junit-jupiter:5.7.0")
    
    // Testcontainers for MongoDB Integration Tests
    testImplementation("org.testcontainers:testcontainers:1.19.3")
    testImplementation("org.testcontainers:mongodb:1.19.3")
    testImplementation("org.testcontainers:postgresql:1.19.3")
    testImplementation("org.testcontainers:junit-jupiter:1.19.3")
    
    // AssertJ for fluent assertions
    testImplementation("org.assertj:assertj-core:3.24.2")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStandardStreams = false
    }
}

tasks.compileJava {
    options.encoding = "UTF-8"
    options.release.set(21)
}

tasks.compileTestJava {
    options.encoding = "UTF-8"
}
