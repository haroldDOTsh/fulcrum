plugins {
    id("java-library")
    id("maven-publish")
}

repositories {
    mavenCentral()
}

dependencies {
    // Module dependencies
    api(project(":message-bus-api"))
    
    // Optional Redis support for implementations
    compileOnly("redis.clients:jedis:5.0.0")
    compileOnly("io.lettuce:lettuce-core:6.2.6.RELEASE")
    
    // Testing
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.0")
    testImplementation("org.mockito:mockito-core:5.5.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
    withSourcesJar()
    withJavadocJar()
}

tasks.test {
    useJUnitPlatform()
}

publishing {
    publications {
        named<MavenPublication>("maven") {
            artifactId = "server-lifecycle-api"
        }
    }
}