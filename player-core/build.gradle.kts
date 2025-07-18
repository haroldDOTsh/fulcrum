import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import xyz.jpenilla.runpaper.task.RunServer

plugins {
    java
    id("com.gradleup.shadow") version "9.0.0-beta17"
    id("xyz.jpenilla.run-paper") version "2.3.1"
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.18"
}

group = "sh.harold"
version = "0.0.1"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://oss.sonatype.org/content/groups/public/")
}

dependencies {
    implementation(project(":data-api"))
    implementation(project(":message-api"))
    implementation(project(":rank-api"))

    // Paper API (temporary fallback until userdev configuration is resolved)
    paperweightDevelopmentBundle("io.papermc.paper:dev-bundle:1.21.7-R0.1-SNAPSHOT")

    // Other runtime deps
    implementation("org.mongodb:mongodb-driver-sync:4.11.1")
    implementation("org.yaml:snakeyaml:2.2")
    implementation("io.github.classgraph:classgraph:4.8.173")

    // Redis dependencies
    implementation("redis.clients:jedis:5.1.0")
    implementation("org.apache.commons:commons-pool2:2.12.0")

    // (Optional test setup)
    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
}

val targetJavaVersion = 21

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
    filesMatching("plugin.yml") {
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

    relocate("org.yaml.snakeyaml", "sh.harold.libraries.snakeyaml")      // Actual package in SnakeYAML 2.2
    relocate("com.google", "sh.harold.libraries.google")       // From gson 2.11.0
    relocate(
        "com.fasterxml.jackson",
        "sh.harold.libraries.jackson"
    )      // From jackson-databind, jackson-core, jackson-annotations
    relocate("com.mongodb", "sh.harold.libraries.mongodb")                // From mongodb-driver-sync
    relocate("org.bson", "sh.harold.libraries.bson")                      // From bson + bson-record-codec

    relocate("sh.harold.fulcrum.api.data", "sh.harold.internal.api.data")
    relocate("sh.harold.fulcrum.api.message", "sh.harold.internal.api.message")
}



tasks.named("build") {
    dependsOn(tasks.named("shadowJar"))
}

// Ensure runServer uses the shaded jar
tasks.named<RunServer>("runServer") {
    minecraftVersion("1.21.7")
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
