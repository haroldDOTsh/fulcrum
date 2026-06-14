import java.util.zip.ZipFile

plugins {
    id("java-library")
    id("maven-publish")
    id("xyz.jpenilla.run-velocity") version "3.0.2"
    id("com.gradleup.shadow") version "9.4.2"
}

group = "sh.harold.fulcrum"
version = "1.5.0"

repositories {
    mavenCentral()
    maven { url = uri("https://repo.papermc.io/repository/maven-public/") }
}

dependencies {
    api(project(":message-bus-api"))
    api(project(":data-api")) {
        exclude(group = "org.postgresql", module = "postgresql")
        exclude(group = "com.zaxxer", module = "HikariCP")
    }
    
    // Velocity API
    compileOnly("com.velocitypowered:velocity-api:3.4.0-SNAPSHOT")
    annotationProcessor("com.velocitypowered:velocity-api:3.4.0-SNAPSHOT")
    
    // Redis client - using Lettuce for consistency with Paper servers
    implementation("io.lettuce:lettuce-core:6.3.0.RELEASE")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.16.0")
    
    // Netty for network functionality
    implementation("io.netty:netty-all:4.1.100.Final")
    
    // YAML configuration
    implementation("org.yaml:snakeyaml:2.0")

    implementation("sh.harold.creative:message-core:v6")
    implementation("sh.harold.creative:message-velocity:v6")
    
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
    exclude("META-INF/native-image/**")
    exclude("migrations/**")
    exclude("sh/harold/fulcrum/api/data/impl/postgres/**")
    exclude("sh/harold/fulcrum/api/data/impl/authority/PostgresDataAuthority*.class")
    exclude("sh/harold/fulcrum/api/data/impl/authority/events/PostgresAuthorityEventDispatcher*.class")
    exclude("sh/harold/fulcrum/api/data/impl/world/PostgresWorldMapStore*.class")
    dependencies {
        exclude(dependency("org.postgresql:postgresql:.*"))
        exclude(dependency("com.zaxxer:HikariCP:.*"))
    }
    
    // Relocate dependencies to avoid conflicts
    // Do NOT relocate SLF4J as Velocity provides and injects it
    relocate("io.lettuce", "sh.harold.fulcrum.velocity.libs.lettuce")
    relocate("io.netty", "sh.harold.fulcrum.velocity.libs.netty")
    relocate("reactor", "sh.harold.fulcrum.velocity.libs.reactor")
    relocate("com.fasterxml.jackson", "sh.harold.fulcrum.velocity.libs.jackson")
    relocate("org.yaml.snakeyaml", "sh.harold.fulcrum.velocity.libs.snakeyaml")
    
    // Minimize JAR
    minimize {
        exclude(dependency("io.lettuce:lettuce-core"))
        exclude(dependency("com.fasterxml.jackson.core:jackson-databind"))
        exclude(dependency("org.yaml:snakeyaml"))
    }
    
    mergeServiceFiles()
}

val verifyGameNodeCustody by tasks.registering {
    group = "verification"
    description = "Verifies the Velocity game-node artifact does not carry direct store tooling."
    dependsOn(tasks.named("jar"))
    dependsOn(tasks.named("shadowJar"))

    val sourceFiles = fileTree("src/main/java") {
        include("**/*.java")
    }
    val resourceFiles = fileTree("src/main/resources") {
        include("**/*.yml", "**/*.yaml", "**/*.properties")
    }
    val reportFile = layout.buildDirectory.file("reports/game-node-custody.txt")
    inputs.files(sourceFiles)
    inputs.files(resourceFiles)
    inputs.files(configurations.runtimeClasspath)
    inputs.file(tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar").flatMap { it.archiveFile })
    outputs.file(reportFile)

    doLast {
        val negativeCapabilityManifest = "META-INF/fulcrum/game-node-negative-capabilities.properties"
        val forbiddenImports = listOf(
            "sh.harold.fulcrum.api.data.impl.postgres",
            "sh.harold.fulcrum.api.data.impl.authority.PostgresDataAuthority",
            "sh.harold.fulcrum.api.data.impl.authority.events.PostgresAuthorityEventDispatcher",
            "sh.harold.fulcrum.api.data.impl.world.PostgresWorldMapStore",
            "org.postgresql",
            "com.zaxxer.hikari",
            "java.sql",
            "javax.sql"
        )
        val forbiddenDependencies = mapOf(
            "org.postgresql:postgresql" to "PostgreSQL JDBC driver",
            "com.zaxxer:HikariCP" to "HikariCP direct database pool"
        )
        val forbiddenJarEntries = mapOf(
            "org/postgresql/" to "PostgreSQL JDBC classes",
            "com/zaxxer/hikari/" to "HikariCP classes",
            "sh/harold/fulcrum/api/data/impl/postgres/" to "Postgres adapter classes",
            "sh/harold/fulcrum/api/data/impl/authority/PostgresDataAuthority" to "Postgres authority writer",
            "sh/harold/fulcrum/api/data/impl/authority/events/PostgresAuthorityEventDispatcher" to "Postgres event dispatcher",
            "sh/harold/fulcrum/api/data/impl/world/PostgresWorldMapStore" to "Postgres world map writer",
            "migrations/" to "database migration resources"
        )
        val forbiddenMetadataEntryPrefixes = listOf(
            "META-INF/services/",
            "META-INF/native-image/"
        )
        val forbiddenMetadataFragments = mapOf(
            "sh.harold.fulcrum.api.data.impl.postgres." to "Postgres adapter metadata",
            "sh.harold.fulcrum.api.data.impl.authority.PostgresDataAuthority" to "Postgres authority writer metadata",
            "sh.harold.fulcrum.api.data.impl.authority.events.PostgresAuthorityEventDispatcher" to "Postgres event dispatcher metadata",
            "sh.harold.fulcrum.api.data.impl.world.PostgresWorldMapStore" to "Postgres world map writer metadata",
            "org.postgresql" to "PostgreSQL JDBC metadata",
            "com.zaxxer.hikari" to "Hikari direct database pool metadata"
        )
        val forbiddenResourceFragments = mapOf(
            "jdbc:" to "JDBC URL",
            "jdbc-url" to "JDBC URL key",
            "postgres:" to "Postgres config section",
            "postgresql" to "Postgres credential material",
            "hikari" to "Hikari direct database pool config",
            "com.zaxxer" to "Hikari direct database pool class",
            "org.postgresql" to "PostgreSQL JDBC class",
            "mongodb" to "MongoDB direct store config",
            "mongo:" to "MongoDB direct store config section",
            "cassandra" to "Cassandra direct store config",
            "mysql" to "MySQL direct store config",
            "mariadb" to "MariaDB direct store config",
            "authority.mode=local" to "local authority mode",
            "mode: local" to "local authority mode",
            "mode: \"local\"" to "local authority mode",
            "mode: 'local'" to "local authority mode"
        )

        val violations = mutableListOf<String>()
        sourceFiles.files.forEach { source ->
            source.readLines().forEachIndexed { index, line ->
                val trimmed = line.trim()
                if (trimmed.startsWith("import ")) {
                    forbiddenImports.firstOrNull { trimmed.contains(it) }?.let { forbidden ->
                        violations += "${source.relativeTo(projectDir)}:${index + 1} imports forbidden game-node store type $forbidden"
                    }
                }
            }
        }
        resourceFiles.files.forEach { resource ->
            resource.readLines().forEachIndexed { index, line ->
                val normalized = line.trim().lowercase()
                forbiddenResourceFragments.firstNotNullOfOrNull { (fragment, reason) ->
                    if (normalized.contains(fragment)) {
                        "${resource.relativeTo(projectDir)}:${index + 1} contains forbidden game-node config: $reason"
                    } else {
                        null
                    }
                }?.let(violations::add)
            }
        }

        configurations.runtimeClasspath.get().resolvedConfiguration.resolvedArtifacts.forEach { artifact ->
            val coordinate = "${artifact.moduleVersion.id.group}:${artifact.name}"
            forbiddenDependencies[coordinate]?.let { reason ->
                violations += "runtimeClasspath includes $coordinate (${artifact.moduleVersion.id.version}): $reason"
            }
        }

        val jarFile = tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar").get().archiveFile.get().asFile
        ZipFile(jarFile).use { zip ->
            val entries = zip.entries().asSequence().map { it.name }.toList()
            if (!entries.contains(negativeCapabilityManifest)) {
                violations += "${jarFile.name} is missing $negativeCapabilityManifest"
            }
            forbiddenJarEntries.forEach { (prefix, reason) ->
                entries.firstOrNull { it.startsWith(prefix) }?.let { entry ->
                    violations += "${jarFile.name} contains $entry: $reason"
                }
            }
            entries
                .filter { entry ->
                    !entry.endsWith("/")
                        && forbiddenMetadataEntryPrefixes.any { prefix -> entry.startsWith(prefix) }
                }
                .forEach { entry ->
                    val content = zip.getInputStream(zip.getEntry(entry)).bufferedReader().use { it.readText() }
                    forbiddenMetadataFragments.forEach { (fragment, reason) ->
                        if (content.contains(fragment)) {
                            violations += "${jarFile.name} contains $entry referencing $fragment: $reason"
                        }
                    }
                }
        }

        val report = reportFile.get().asFile
        report.parentFile.mkdirs()
        report.writeText(
            if (violations.isEmpty()) {
                "Velocity game-node custody check passed.\n"
            } else {
                violations.joinToString(separator = System.lineSeparator(), postfix = System.lineSeparator())
            }
        )
        if (violations.isNotEmpty()) {
            throw GradleException("Velocity game-node custody check failed. See ${report.absolutePath}")
        }
    }
}

tasks.build {
    dependsOn(tasks.shadowJar)
}

tasks.check {
    dependsOn(verifyGameNodeCustody)
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
        velocityVersion("3.4.0-SNAPSHOT")
        
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
            "-XX:MaxInlineLevel=15",
            "-Dvelocity.packet-decode-logging=true"
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
