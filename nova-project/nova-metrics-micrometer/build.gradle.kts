dependencies {
    api(project(":nova-project:nova-core"))
    api("io.micrometer:micrometer-core:1.14.0")

    testImplementation(platform("org.junit:junit-bom:5.12.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("io.micrometer:micrometer-core:1.14.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
