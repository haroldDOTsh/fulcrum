plugins {
    id("java")
    id("com.gradleup.shadow") version "9.0.0-beta17"
    id("application")
}

group = "sh.harold.fulcrum"
version = "1.0.0-SNAPSHOT"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

application {
    mainClass.set("sh.harold.fulcrum.registry.RegistryService")
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // Internal dependencies - using the shared core contracts
    implementation(project(":common-api"))
    implementation(project(":data-api")) {
        exclude(group = "org.slf4j", module = "slf4j-simple")
    }
    implementation("org.mongodb:mongodb-driver-sync:4.11.1")
    
    // Redis client (same as used in runtime)
    implementation("io.lettuce:lettuce-core:6.3.0.RELEASE")
    
    // Reactive Streams (required by Lettuce for reactive commands)
    implementation("io.projectreactor:reactor-core:3.6.0")
    implementation("org.reactivestreams:reactive-streams:1.0.4")
    
    // Netty dependencies (explicit to ensure all are included)
    implementation("io.netty:netty-common:4.1.101.Final")
    implementation("io.netty:netty-buffer:4.1.101.Final")
    implementation("io.netty:netty-codec:4.1.101.Final")
    implementation("io.netty:netty-handler:4.1.101.Final")
    implementation("io.netty:netty-transport:4.1.101.Final")
    implementation("io.netty:netty-resolver:4.1.101.Final")
    implementation("io.netty:netty-transport-native-unix-common:4.1.101.Final")
    
    // JSON processing
    implementation("com.fasterxml.jackson.core:jackson-databind:2.16.0")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.16.0")
    
    // Logging
    implementation("org.slf4j:slf4j-api:2.0.9")
    implementation("ch.qos.logback:logback-classic:1.4.14")
    implementation("org.fusesource.jansi:jansi:2.4.1") // For ANSI color support in Windows
    
    // Configuration
    implementation("org.yaml:snakeyaml:2.2")
    
    // Scheduling
    implementation("org.quartz-scheduler:quartz:2.3.2")
    
    // Testing
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")
    testImplementation("org.mockito:mockito-core:5.7.0")
    testImplementation("org.assertj:assertj-core:3.24.2")
}

tasks.test {
    useJUnitPlatform()
}

tasks.jar {
    manifest {
        attributes["Main-Class"] = "sh.harold.fulcrum.registry.RegistryService"
    }
}

tasks.shadowJar {
    archiveClassifier.set("")
    archiveBaseName.set("registry-service")
    mergeServiceFiles()
    
    // Include all runtime dependencies
    configurations = listOf(project.configurations.runtimeClasspath.get())
    
    // Don't minimize - we need all classes including inner classes
    // minimize {
    //     exclude(dependency("ch.qos.logback:.*"))
    //     exclude(dependency("org.slf4j:.*"))
    // }
    
    // Merge duplicate files to avoid conflicts
    exclude("META-INF/*.SF")
    exclude("META-INF/*.DSA")
    exclude("META-INF/*.RSA")
    exclude("META-INF/DEPENDENCIES")
    exclude("META-INF/LICENSE")
    exclude("META-INF/LICENSE.txt")
    exclude("META-INF/NOTICE")
    exclude("META-INF/NOTICE.txt")
    
    // Transform services files
    transform(com.github.jengelman.gradle.plugins.shadow.transformers.ServiceFileTransformer::class.java)
    
    manifest {
        attributes["Main-Class"] = "sh.harold.fulcrum.registry.RegistryService"
    }
}

// Fix the task dependencies for distribution tasks
tasks.named("distZip") {
    dependsOn(tasks.shadowJar)
}

tasks.named("distTar") {
    dependsOn(tasks.shadowJar)
}

tasks.named("startScripts") {
    dependsOn(tasks.shadowJar)
}

// Fix dependency for shadow plugin tasks
tasks.named("startShadowScripts") {
    dependsOn(tasks.jar)
}

// Custom run task for the Registry Service using InteractiveLauncher for proper console support
tasks.register<JavaExec>("runRegistry") {
    group = "application"
    description = "Run the Fulcrum Registry Service with interactive console support"
    
    // Use the InteractiveLauncher to spawn a subprocess with proper IO handling
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("sh.harold.fulcrum.registry.console.InteractiveLauncher")
    
    // Set working directory to registry-service for launcher to find JAR
    workingDir = projectDir
    
    // Enable interactive mode for console input
    standardInput = System.`in`
    standardOutput = System.out
    errorOutput = System.err
    
    // Create the run directory if it doesn't exist
    doFirst {
        file("run").mkdirs()
        println("Starting Fulcrum Registry Service with Interactive Console...")
        println("Redis Host: ${System.getenv("REDIS_HOST") ?: "localhost"}")
        println("Redis Port: ${System.getenv("REDIS_PORT") ?: "6379"}")
        println("")
    }
    
    // Environment variables for Redis connection
    environment("REDIS_HOST", System.getenv("REDIS_HOST") ?: "localhost")
    environment("REDIS_PORT", System.getenv("REDIS_PORT") ?: "6379")
    environment("REDIS_PASSWORD", System.getenv("REDIS_PASSWORD") ?: "")
    
    // Ensure we have built the JAR first
    dependsOn("shadowJar")
}

// Alternative task for running without interactive console (for CI/CD)
tasks.register<JavaExec>("runRegistryNoConsole") {
    group = "application"
    description = "Run the Registry Service without interactive console"
    
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("sh.harold.fulcrum.registry.RegistryService")
    
    // Set working directory to registry-service/run for local testing
    workingDir = file("run")
    
    // Create the run directory if it doesn't exist
    doFirst {
        workingDir.mkdirs()
        println("Starting Fulcrum Registry Service (No Console)...")
        println("Working directory: ${workingDir.absolutePath}")
        println("Redis Host: ${System.getenv("REDIS_HOST") ?: "localhost"}")
        println("Redis Port: ${System.getenv("REDIS_PORT") ?: "6379"}")
    }
    
    // JVM options
    jvmArgs = listOf(
        "-Xms256m",
        "-Xmx512m",
        "-Dlogback.configurationFile=${projectDir}/src/main/resources/logback.xml"
    )
    
    // Environment variables for Redis connection
    environment("REDIS_HOST", System.getenv("REDIS_HOST") ?: "localhost")
    environment("REDIS_PORT", System.getenv("REDIS_PORT") ?: "6379")
    environment("REDIS_PASSWORD", System.getenv("REDIS_PASSWORD") ?: "")
}

// Also configure the default run task to use the same working directory
tasks.named<JavaExec>("run") {
    workingDir = file("run")
    
    doFirst {
        workingDir.mkdirs()
    }
    
    // Environment variables for Redis connection
    environment("REDIS_HOST", System.getenv("REDIS_HOST") ?: "localhost")
    environment("REDIS_PORT", System.getenv("REDIS_PORT") ?: "6379")
    environment("REDIS_PASSWORD", System.getenv("REDIS_PASSWORD") ?: "")
}
