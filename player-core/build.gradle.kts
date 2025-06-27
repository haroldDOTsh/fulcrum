plugins {
    java
    id("xyz.jpenilla.run-paper") version "2.3.1"
}

group = "sh.harold"
version = "0.0.1"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/") {
        name = "papermc-repo"
    }
    maven("https://oss.sonatype.org/content/groups/public/") {
        name = "sonatype"
    }
}

dependencies {
    implementation("io.papermc.paper:paper-api:1.21.6-R0.1-SNAPSHOT")
    implementation(project(":player-api"))
    implementation("org.mongodb:mongodb-driver-sync:4.11.1")
}

val targetJavaVersion = 21

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(targetJavaVersion))
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    if (JavaVersion.current().isJava10Compatible) {
        options.release.set(targetJavaVersion)
    }
}

tasks.named<Copy>("processResources") {
    val props = mapOf("version" to version)
    inputs.properties(props)
    filteringCharset = "UTF-8"
    filesMatching("plugin.yml") {
        expand(props)
    }
}

tasks.named("runServer", xyz.jpenilla.runpaper.task.RunServer::class) {
    minecraftVersion("1.21.6")
}
