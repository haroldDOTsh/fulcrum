plugins {
    id("java-library")
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // Paper API for Bukkit/Paper types
    compileOnly("io.papermc.paper:paper-api:1.21.7-R0.1-SNAPSHOT")
    
    // data-api for annotations used in MonthlyRankData
    implementation(project(":data-api"))
    
    // JUnit 5 for testing
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    // Paper API for test classpath
    testImplementation("io.papermc.paper:paper-api:1.21.7-R0.1-SNAPSHOT")
    // Mockito for testing
    testImplementation("org.mockito:mockito-core:5.2.0")
    testImplementation("org.mockito:mockito-junit-jupiter:5.2.0")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

tasks.test {
    useJUnitPlatform()
}