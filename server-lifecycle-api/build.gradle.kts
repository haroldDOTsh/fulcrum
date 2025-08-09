plugins {
    id("java-library")
    id("maven-publish")
}

dependencies {
    // Core dependencies
    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
    compileOnly("com.velocitypowered:velocity-api:3.4.0-SNAPSHOT")
    
    // Module dependencies
    api(project(":module-api"))
    api(project(":message-bus-api"))
    
    // Redis for registry implementation hints
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
        create<MavenPublication>("maven") {
            from(components["java"])
            
            groupId = "sh.harold.fulcrum"
            artifactId = "server-lifecycle-api"
            version = project.version.toString()
        }
    }
}