import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import java.util.zip.ZipFile
import xyz.jpenilla.runpaper.task.RunServer

plugins {
    java
    id("com.gradleup.shadow") version "9.4.2"
    id("xyz.jpenilla.run-paper") version "3.0.2"
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.21"
}

group = "sh.harold.fulcrum"
version = "1.4.1"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://oss.sonatype.org/content/groups/public/")
    maven("https://maven.enginehub.org/repo/") // FAWE repository
}

dependencies {
    implementation(project(":message-bus-api"))
    implementation(project(":data-api")) {
        exclude(group = "org.postgresql", module = "postgresql")
        exclude(group = "com.zaxxer", module = "HikariCP")
    }

    // Paper 26.1 dev bundle includes the Paper API and server internals for userdev.
    paperweight.paperDevBundle("26.1.2.build.+")

    // Other runtime deps
    implementation("org.yaml:snakeyaml:2.2")
    implementation("io.github.classgraph:classgraph:4.8.173")

    implementation("sh.harold.creative:message-core:v6")
    implementation("sh.harold.creative:message-paper:v6")
    implementation("sh.harold.creative:menu-core:v6")
    implementation("sh.harold.creative:menu-paper:v6")
    implementation("sh.harold.creative:sound-paper:v6")
    implementation("sh.harold.creative:scoreboard-paper:v6")
    
    // Jackson dependencies (required for message bus serialization)
    implementation("com.fasterxml.jackson.core:jackson-core:2.15.2")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.15.2")
    implementation("com.fasterxml.jackson.core:jackson-annotations:2.15.2")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.15.2")

    // Redis dependencies (using Lettuce to match proxy implementation)
    implementation("io.lettuce:lettuce-core:6.3.0.RELEASE")
    implementation("org.apache.commons:commons-pool2:2.12.0") // Still needed for connection pooling
    
    // World Editing
    compileOnly("com.fastasyncworldedit:FastAsyncWorldEdit-Core:2.11.2")
    compileOnly("com.fastasyncworldedit:FastAsyncWorldEdit-Bukkit:2.11.2") {
        isTransitive = false
    }

    // (Optional test setup)
    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
}

val targetJavaVersion = 25

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(targetJavaVersion))
}



tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(targetJavaVersion)
}

tasks.named<Copy>("processResources") {
    val props = mapOf("version" to version)
    inputs.properties(props)
    filteringCharset = "UTF-8"
    filesMatching("paper-plugin.yml") {
        expand(props)
    }
}

// Disable plain jar — shadow will be the output
tasks.named("jar") {
    enabled = false
}

// Create the uberjar and set output
tasks.named<ShadowJar>("shadowJar") {
    archiveClassifier.set("")
    configurations = listOf(project.configurations.runtimeClasspath.get())

    // Exclude duplicate or non-essential metadata to avoid remapper errors
    exclude("META-INF/LICENSE", "META-INF/NOTICE", "META-INF/*.kotlin_module")
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

    relocate("org.yaml.snakeyaml", "sh.harold.libraries.snakeyaml")      // Actual package in SnakeYAML 2.2
    relocate("com.google", "sh.harold.libraries.google")       // From gson 2.11.0
    relocate(
        "com.fasterxml.jackson",
        "sh.harold.libraries.jackson"
    )      // From jackson-databind, jackson-core, jackson-annotations

    // relocate("sh.harold.fulcrum.api.data", "sh.harold.internal.api.data")
    // relocate("sh.harold.fulcrum.api.rank", "sh.harold.internal.api.rank")
    // relocate("sh.harold.fulcrum.api.module", "sh.harold.internal.api.module")
}

val verifyGameNodeCustody by tasks.registering {
    group = "verification"
    description = "Verifies the Paper game-node artifact does not carry direct store tooling."
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
    inputs.file(tasks.named<ShadowJar>("shadowJar").flatMap { it.archiveFile })
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

        val jarFile = tasks.named<ShadowJar>("shadowJar").get().archiveFile.get().asFile
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
                "Paper game-node custody check passed.\n"
            } else {
                violations.joinToString(separator = System.lineSeparator(), postfix = System.lineSeparator())
            }
        )
        if (violations.isNotEmpty()) {
            throw GradleException("Paper game-node custody check failed. See ${report.absolutePath}")
        }
    }
}



tasks.named("build") {
    dependsOn(tasks.named("shadowJar"))
}

tasks.named("check") {
    dependsOn(verifyGameNodeCustody)
}

// Ensure runServer uses the shaded jar
tasks.named<RunServer>("runServer") {
    systemProperty("com.mojang.eula.agree", "true")
    minecraftVersion("26.1.2")
    dependsOn(tasks.named("shadowJar"))
    pluginJars.setFrom(tasks.named<ShadowJar>("shadowJar").map { it.archiveFile })
}

// Optional: configure test logging
tasks.named<Test>("test") {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStandardStreams = true
    }
}
