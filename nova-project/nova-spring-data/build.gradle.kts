dependencies {
    api(project(":nova-project:nova-core"))
    api("org.springframework:spring-context:6.2.0")

    testImplementation(project(":nova-project:nova-r2dbc"))
    testImplementation(project(":nova-project:nova-dialects:nova-dialect-h2"))
    testImplementation(platform("org.junit:junit-bom:5.12.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("io.projectreactor:reactor-test:3.7.3")
    testImplementation("io.r2dbc:r2dbc-h2:1.0.0.RELEASE")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
