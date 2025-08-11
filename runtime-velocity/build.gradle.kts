plugins {
    id("java-library")
    id("maven-publish")
    id("xyz.jpenilla.run-velocity") version "2.3.1"
    id("com.gradleup.shadow") version "9.0.0-beta17"
}

group = "sh.harold.fulcrum"
version = "1.5.0"

repositories {
    mavenCentral()
    maven { url = uri("https://repo.papermc.io/repository/maven-public/") }
}

dependencies {
    api(project(":message-bus-api"))
    api(project(":server-lifecycle-api"))
    
    // Velocity API
    compileOnly("com.velocitypowered:velocity-api:3.3.0-SNAPSHOT")
    annotationProcessor("com.velocitypowered:velocity-api:3.3.0-SNAPSHOT")
    
    // Redis client
    implementation("io.lettuce:lettuce-core:6.3.0.RELEASE")
    
    // JSON processing
    implementation("com.fasterxml.jackson.core:jackson-databind:2.16.1")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.16.1")
    
    // Logging
    implementation("org.slf4j:slf4j-api:2.0.9")
    
    // Testing
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")
    testImplementation("org.mockito:mockito-core:5.8.0")
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

tasks.jar {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

// Shadow JAR configuration
tasks.shadowJar {
    archiveClassifier.set("")
    
    // Include dependencies
    configurations = listOf(project.configurations.runtimeClasspath.get())
    
    // Relocate dependencies to avoid conflicts
    // Do NOT relocate SLF4J as Velocity provides and injects it
    relocate("com.fasterxml.jackson", "sh.harold.fulcrum.velocity.libs.jackson")
    relocate("io.lettuce", "sh.harold.fulcrum.velocity.libs.lettuce")
    relocate("com.google.gson", "sh.harold.fulcrum.velocity.libs.gson")
    relocate("reactor", "sh.harold.fulcrum.velocity.libs.reactor")
    relocate("io.netty", "sh.harold.fulcrum.velocity.libs.netty")
    
    // Minimize JAR
    minimize {
        exclude(dependency("com.fasterxml.jackson.*:.*"))
        exclude(dependency("io.lettuce:.*"))
    }
    
    mergeServiceFiles()
}

tasks.build {
    dependsOn(tasks.shadowJar)
}

publishing {
    publications {
        create<MavenPublication>("velocityPlugin") {
            from(components["java"])
        }
    }
}

// Run-task plugin configuration for local development
tasks {
    runVelocity {
        // Use the latest stable Velocity version
        velocityVersion("3.3.0-SNAPSHOT")
        
        // Set the run directory
        runDirectory = projectDir.resolve("run")
        
        // JVM arguments for better performance during development
        jvmArgs(
            "-Xms512M",
            "-Xmx1G",
            "-XX:+UseG1GC",
            "-XX:G1HeapRegionSize=4M",
            "-XX:+UnlockExperimentalVMOptions",
            "-XX:+ParallelRefProcEnabled",
            "-XX:+AlwaysPreTouch",
            "-XX:MaxInlineLevel=15"
        )
        
        // Automatically copy the built plugin jar to the plugins folder
        downloadPlugins {
            // Optionally add other plugins for testing
            // Example: url("https://example.com/plugin.jar")
        }
    }
    
    // Clean task for the run directory
    register("cleanRun") {
        group = "run"
        description = "Cleans the Velocity run directory"
        doLast {
            val runDir = projectDir.resolve("run")
            if (runDir.exists()) {
                runDir.deleteRecursively()
                logger.lifecycle("Cleaned run directory: $runDir")
            } else {
                logger.lifecycle("Run directory does not exist: $runDir")
            }
        }
    }
    
    // Build and run task
    register("buildAndRun") {
        group = "run"
        description = "Builds the plugin and starts Velocity"
        dependsOn("build", "runVelocity")
    }
    
    named("runVelocity") {
        mustRunAfter("build")
    }
    
    // Task to prepare a test environment
    register("prepareTestEnvironment") {
        group = "run"
        description = "Prepares the test environment with default configuration"
        dependsOn("build")
        doLast {
            val runDir = projectDir.resolve("run")
            val pluginsDir = runDir.resolve("plugins")
            
            // Create directories (run-velocity plugin handles copying the JAR automatically)
            pluginsDir.mkdirs()
            
            // Create a basic velocity.toml if it doesn't exist
            val velocityConfig = runDir.resolve("velocity.toml")
            if (!velocityConfig.exists()) {
                velocityConfig.writeText("""
                    # Velocity configuration for development
                    config-version = "2.7"
                    
                    # Network settings
                    bind = "0.0.0.0:25577"
                    motd = "&3Fulcrum Velocity Test Server"
                    show-max-players = 100
                    online-mode = true
                    
                    # Forwarding
                    player-info-forwarding-mode = "modern"
                    forwarding-secret-file = "forwarding.secret"
                    
                    # Advanced
                    [advanced]
                    compression-threshold = 256
                    compression-level = -1
                    login-ratelimit = 3000
                    
                    # Query
                    [query]
                    enabled = false
                    
                    # Servers (add your backend servers here)
                    [servers]
                    # Example:
                    # lobby = "127.0.0.1:25565"
                    # survival = "127.0.0.1:25566"
                    
                    try = ["lobby"]
                    
                    [forced-hosts]
                    # "lobby.example.com" = ["lobby"]
                """.trimIndent())
                logger.lifecycle("Created default velocity.toml configuration")
            }
            
            // Create forwarding secret
            val forwardingSecret = runDir.resolve("forwarding.secret")
            if (!forwardingSecret.exists()) {
                // Generate a simple secret for development
                forwardingSecret.writeText("fulcrum-dev-secret-" + System.currentTimeMillis())
                logger.lifecycle("Created forwarding secret file")
            }
        }
    }
}

// Ensure the plugin jar is copied after building
tasks.named("runVelocity") {
    dependsOn("prepareTestEnvironment")
}