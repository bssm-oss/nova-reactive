dependencies {
    api("io.projectreactor:reactor-core:3.7.3")
    api("io.r2dbc:r2dbc-spi:1.0.0.RELEASE")

    testImplementation(platform("org.junit:junit-bom:5.12.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("io.projectreactor:reactor-test:3.7.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
