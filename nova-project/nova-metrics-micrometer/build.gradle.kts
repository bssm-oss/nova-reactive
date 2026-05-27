dependencies {
    api(project(":nova-project:nova-core"))
    api("io.micrometer:micrometer-core:1.14.0")

    testImplementation("io.micrometer:micrometer-core:1.14.0")
}
