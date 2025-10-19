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
    api("net.kyori:adventure-api:4.14.0")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

tasks.compileJava {
    options.encoding = "UTF-8"
    options.release.set(21)
}

tasks.compileTestJava {
    options.encoding = "UTF-8"
}
