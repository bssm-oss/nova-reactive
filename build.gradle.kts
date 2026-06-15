import org.gradle.plugins.signing.SigningExtension

plugins {
    java
}

allprojects {
    group = "io.github.bssm-oss"
    // 기본은 2.1.0-SNAPSHOT(다음 dev 사이클). release 워크플로가 git tag(예: v2.0.0)에서 파생한 값을
    // -Pnova.version=2.0.0로 주입하면 그 값을 우선 사용한다. SNAPSHOT 버전은 Central snapshots
    // 저장소로, release 버전은 Central Portal staging으로 자동 라우팅된다(publishing repositories 참고).
    version = (findProperty("nova.version") as String?)?.takeIf { it.isNotBlank() } ?: "2.1.0-SNAPSHOT"
}

subprojects {
    if (childProjects.isNotEmpty()) return@subprojects

    apply(plugin = "java-library")
    apply(plugin = "maven-publish")

    // Centralized JUnit 5 test dependencies for every leaf module. In a
    // `subprojects {}` block the typed `testImplementation(...)` /
    // `testRuntimeOnly(...)` accessors are not available, so the configuration
    // names are addressed as strings; this works because `java-library` has
    // already been applied above, which registers those configurations.
    dependencies {
        "testImplementation"(platform("org.junit:junit-bom:5.12.0"))
        "testImplementation"("org.junit.jupiter:junit-jupiter")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }

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
                    name.set("Nova :: ${project.name}")
                    // All leaf modules share this common description. To give a
                    // module its own POM description, set `description = "..."`
                    // in that module's own build.gradle.kts; the per-module value
                    // is picked up here via project.description.
                    description.set(
                        project.description
                            ?: "Nova: lightweight reactive ORM for Java 21 on R2DBC and Project Reactor.",
                    )
                    url.set("https://github.com/bssm-oss/nova-reactive")

                    licenses {
                        license {
                            name.set("Apache License 2.0")
                            url.set("https://www.apache.org/licenses/LICENSE-2.0")
                        }
                    }

                    developers {
                        developer {
                            id.set("bssm-oss")
                            name.set("BSSM OSS")
                            organization.set("BSSM OSS")
                            organizationUrl.set("https://github.com/bssm-oss")
                        }
                    }

                    scm {
                        connection.set("scm:git:https://github.com/bssm-oss/nova-reactive.git")
                        developerConnection.set("scm:git:ssh://git@github.com/bssm-oss/nova-reactive.git")
                        url.set("https://github.com/bssm-oss/nova-reactive")
                    }
                }
            }
        }

        repositories {
            maven {
                // Sonatype Central Portal. The legacy OSSRH host (s01.oss.sonatype.org)
                // reached end of life on 2025-06-30, so publishing now targets the
                // Central Portal OSSRH Staging API for releases and the Central
                // snapshots repository for SNAPSHOT versions.
                name = "central"
                val centralReleasesUrl =
                    uri("https://ossrh-staging-api.central.sonatype.com/service/local/staging/deploy/maven2/")
                val centralSnapshotsUrl =
                    uri("https://central.sonatype.com/repository/maven-snapshots/")
                url = if (version.toString().endsWith("SNAPSHOT")) centralSnapshotsUrl else centralReleasesUrl
                credentials {
                    // Treat blank values as absent so an exported-but-empty env var
                    // (e.g. `export CENTRAL_USERNAME=`) does not authenticate with "".
                    // Falls back to the legacy ossrh* property/env names for continuity.
                    username = (findProperty("centralUsername") as String?)
                        ?.takeIf { it.isNotBlank() }
                        ?: System.getenv("CENTRAL_USERNAME")?.takeIf { it.isNotBlank() }
                        ?: (findProperty("ossrhUsername") as String?)?.takeIf { it.isNotBlank() }
                        ?: System.getenv("OSSRH_USERNAME")?.takeIf { it.isNotBlank() }
                    password = (findProperty("centralPassword") as String?)
                        ?.takeIf { it.isNotBlank() }
                        ?: System.getenv("CENTRAL_PASSWORD")?.takeIf { it.isNotBlank() }
                        ?: (findProperty("ossrhPassword") as String?)?.takeIf { it.isNotBlank() }
                        ?: System.getenv("OSSRH_PASSWORD")?.takeIf { it.isNotBlank() }
                }
            }
        }
    }

    // Sign published artifacts only when a non-blank PGP key is supplied, so that
    // `./gradlew build` and `publishToMavenLocal` succeed locally without keys.
    // Blank values (e.g. `export SIGNING_KEY=`) are treated as absent; otherwise
    // the signing plugin would activate and then fail with an empty key.
    val signingKey = ((findProperty("signingKey") as String?) ?: System.getenv("SIGNING_KEY"))
        ?.takeIf { it.isNotBlank() }
    val signingPassword = ((findProperty("signingPassword") as String?) ?: System.getenv("SIGNING_PASSWORD"))
        ?.takeIf { it.isNotBlank() }
    if (signingKey != null) {
        apply(plugin = "signing")
        extensions.configure<SigningExtension> {
            useInMemoryPgpKeys(signingKey, signingPassword)
            sign(extensions.getByType<PublishingExtension>().publications["maven"])
        }
    }
}
