allprojects {
    group = "sh.harold.fulcrum"
    version = "4.16.1"

    repositories {
        mavenCentral()
        maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/")
        maven("https://oss.sonatype.org/content/repositories/snapshots")
    }
}

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "maven-publish")

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
                            name.set("GNU General Public License v3.0")
                            url.set("https://www.gnu.org/licenses/gpl-3.0.html")
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

    dependencies {
        add("testImplementation", "org.junit.jupiter:junit-jupiter:5.9.2")
    }
}

private enum class SemverComponent { MAJOR, MINOR, PATCH }

private fun Project.bumpVersion(component: SemverComponent) {
    val versionPattern = Regex("""version\s*=\s*"(\d+)\.(\d+)\.(\d+)"""")
    val buildScript = buildFile
    val scriptText = buildScript.readText()
    val match = versionPattern.find(scriptText)
        ?: error("Unable to locate version declaration in ${buildScript.path}")

    var major = match.groupValues[1].toInt()
    var minor = match.groupValues[2].toInt()
    var patch = match.groupValues[3].toInt()

    when (component) {
        SemverComponent.MAJOR -> {
            major += 1
            minor = 0
            patch = 0
        }

        SemverComponent.MINOR -> {
            minor += 1
            patch = 0
        }

        SemverComponent.PATCH -> patch += 1
    }

    val newVersion = "$major.$minor.$patch"
    val updatedScript = scriptText.replaceRange(match.range, """version = "$newVersion"""")

    buildScript.writeText(updatedScript)
    version = newVersion
    logger.lifecycle("Updated project version to {}", newVersion)
}

tasks.register("bumpMajor") {
    group = "versioning"
    description = "Increment the major component of the project version and reset minor/patch."
    doLast { project.bumpVersion(SemverComponent.MAJOR) }
}

tasks.register("bumpMinor") {
    group = "versioning"
    description = "Increment the minor component of the project version and reset patch."
    doLast { project.bumpVersion(SemverComponent.MINOR) }
}

tasks.register("bumpPatch") {
    group = "versioning"
    description = "Increment the patch component of the project version."
    doLast { project.bumpVersion(SemverComponent.PATCH) }
}
