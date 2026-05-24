import org.gradle.plugins.signing.SigningExtension

plugins {
    java
}

allprojects {
    group = "io.nova"
    version = "1.0-SNAPSHOT"
}

subprojects {
    if (childProjects.isNotEmpty()) return@subprojects

    apply(plugin = "java-library")
    apply(plugin = "maven-publish")

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion = JavaLanguageVersion.of(21)
        }
        withSourcesJar()
        withJavadocJar()
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }

    extensions.configure<PublishingExtension> {
        publications {
            create<MavenPublication>("maven") {
                from(components["java"])

                pom {
                    // Module-specific name/description fall back to a generic
                    // value so newly added modules inherit valid metadata even
                    // before they declare their own description.
                    name.set("Nova :: ${project.name}")
                    description.set(
                        project.description
                            ?: "Nova: lightweight reactive ORM for Java 21 on R2DBC and Project Reactor.",
                    )
                    url.set("https://github.com/nova-orm/nova")

                    licenses {
                        license {
                            name.set("Apache License 2.0")
                            url.set("https://www.apache.org/licenses/LICENSE-2.0")
                        }
                    }

                    developers {
                        developer {
                            id.set("nova-orm")
                            name.set("Nova ORM Maintainers")
                            email.set("maintainers@nova-orm.example")
                        }
                    }

                    scm {
                        connection.set("scm:git:https://github.com/nova-orm/nova.git")
                        developerConnection.set("scm:git:ssh://git@github.com/nova-orm/nova.git")
                        url.set("https://github.com/nova-orm/nova")
                    }
                }
            }
        }

        repositories {
            maven {
                name = "ossrh"
                val releasesUrl = uri("https://s01.oss.sonatype.org/service/local/staging/deploy/maven2/")
                val snapshotsUrl = uri("https://s01.oss.sonatype.org/content/repositories/snapshots/")
                url = if (version.toString().endsWith("SNAPSHOT")) snapshotsUrl else releasesUrl
                credentials {
                    username = (findProperty("ossrhUsername") as String?) ?: System.getenv("OSSRH_USERNAME")
                    password = (findProperty("ossrhPassword") as String?) ?: System.getenv("OSSRH_PASSWORD")
                }
            }
        }
    }

    // Sign published artifacts only when PGP credentials are supplied, so that
    // `./gradlew build` and `publishToMavenLocal` succeed locally without keys.
    val signingKey = (findProperty("signingKey") as String?) ?: System.getenv("SIGNING_KEY")
    val signingPassword = (findProperty("signingPassword") as String?) ?: System.getenv("SIGNING_PASSWORD")
    if (signingKey != null) {
        apply(plugin = "signing")
        extensions.configure<SigningExtension> {
            useInMemoryPgpKeys(signingKey, signingPassword)
            sign(extensions.getByType<PublishingExtension>().publications["maven"])
        }
    }
}
