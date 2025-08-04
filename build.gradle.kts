allprojects {
    group = "sh.harold.fulcrum"
    version = "1.3.3"

    repositories {
        mavenCentral()
        maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/")
        maven("https://oss.sonatype.org/content/repositories/snapshots")
    }
}

subprojects {
    apply(plugin = "java-library")
    
    // Only apply maven-publish to API modules, not player-core
    if (project.name != "runtime") {
        apply(plugin = "maven-publish")
    }

    configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
        if (project.name != "runtime") {
            withSourcesJar()
            withJavadocJar()
        }
    }

    tasks.withType<JavaCompile> {
        options.encoding = "UTF-8"
    }

    tasks.withType<Test> {
        useJUnitPlatform()
    }

    // Only configure publishing for API modules, not player-core
    if (project.name != "runtime") {
        configure<PublishingExtension> {
            publications {
                create<MavenPublication>("maven") {
                    from(components["java"])
                    
                    pom {
                        name.set(project.name)
                        description.set("${project.name} module of Fulcrum Core")
                        url.set("https://github.com/haroldDOTsh/fulcrum")
                        
                        licenses {
                            license {
                                name.set("MIT License")
                                url.set("https://opensource.org/licenses/MIT")
                            }
                        }
                        
                        developers {
                            developer {
                                id.set("ZECHEESELORD")
                                name.set("Hqrxld")
                            }
                        }
                        
                        scm {
                            connection.set("scm:git:github.com/haroldDOTsh/fulcrum.git")
                            developerConnection.set("scm:git:ssh://github.com/haroldDOTsh/fulcrum.git")
                            url.set("https://github.com/haroldDOTsh/fulcrum")
                        }
                    }
                }
            }
        }
    }

    dependencies {
        add("testImplementation", "org.junit.jupiter:junit-jupiter:5.9.2")
    }
}
