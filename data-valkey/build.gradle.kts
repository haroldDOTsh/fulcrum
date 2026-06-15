plugins {
    id("java-library")
}

group = "sh.harold.fulcrum"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    compileOnly(project(":data-api"))
    implementation("io.lettuce:lettuce-core:6.3.0.RELEASE")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")
    testImplementation("org.assertj:assertj-core:3.24.2")
    testImplementation("org.testcontainers:testcontainers:1.19.3")
    testImplementation(project(":data-api"))
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

tasks.compileJava {
    options.encoding = "UTF-8"
    options.release.set(17)
}

tasks.compileTestJava {
    options.encoding = "UTF-8"
}

tasks.test {
    useJUnitPlatform()
}
