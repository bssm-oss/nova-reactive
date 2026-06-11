dependencies {
    api("io.projectreactor:reactor-core:3.7.3")
    api("io.r2dbc:r2dbc-spi:1.0.0.RELEASE")
    // Nova reuses the real JPA annotations (jakarta.persistence) so entities are
    // byte-for-byte source-compatible with JPA. Honored attributes are a documented
    // subset; unsupported ones (lazy, cascade, ...) are rejected fail-fast at metadata build.
    api("jakarta.persistence:jakarta.persistence-api:3.2.0")

    testImplementation("io.projectreactor:reactor-test:3.7.3")
}
