dependencies {
    api(project(":nova-project:nova-core"))
    api("io.r2dbc:r2dbc-spi:1.0.0.RELEASE")
    api("io.projectreactor:reactor-core:3.7.0")

    testImplementation(platform("org.junit:junit-bom:5.12.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
