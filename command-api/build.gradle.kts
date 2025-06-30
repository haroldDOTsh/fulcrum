plugins {
    id("java-library")
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // Paper API for CommandSender
    compileOnly("io.papermc.paper:paper-api:1.21.6-R0.1-SNAPSHOT")
    // JUnit 5 for testing
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    // MockBukkit for Bukkit API mocking
    implementation("org.mockbukkit.mockbukkit:mockbukkit-v1.21:4.59.0")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

tasks.test {
    useJUnitPlatform()
}
