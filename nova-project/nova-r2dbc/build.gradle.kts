dependencies {
    api(project(":nova-project:nova-core"))
    api("io.r2dbc:r2dbc-spi:1.0.0.RELEASE")
    api("io.projectreactor:reactor-core:3.7.0")

    testImplementation(platform("org.junit:junit-bom:5.12.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("io.projectreactor:reactor-test:3.7.0")
    testImplementation("io.r2dbc:r2dbc-h2:1.0.0.RELEASE")
    testImplementation(project(":nova-project:nova-dialects:nova-dialect-h2"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
