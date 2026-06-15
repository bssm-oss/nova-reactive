plugins {
    application
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

dependencies {
    // Nova (composite-substituted to the local build). Version is nominal — the
    // composite build matches by group:module and supplies the local output.
    implementation("io.github.bssm-oss:nova:2.1.0-SNAPSHOT")
    // R2DBC H2 driver for Nova's reactive path.
    implementation("io.r2dbc:r2dbc-h2:1.0.0.RELEASE")
    // Connection pool — fair comparison vs Hibernate's built-in pool (both capped at 20).
    implementation("io.r2dbc:r2dbc-pool:1.0.2.RELEASE")

    // Hibernate ORM (Jakarta Persistence 3.2, matching Nova's jakarta.persistence-api 3.2.0).
    implementation("org.hibernate.orm:hibernate-core:7.0.0.Final")
    // JDBC H2 driver for Hibernate's blocking path.
    implementation("com.h2database:h2:2.3.232")

    // Reactor (transitive via Nova, declared explicitly for clarity in the harness).
    implementation("io.projectreactor:reactor-core:3.7.3")
}

application {
    mainClass = "io.nova.benchmark.BenchmarkRunner"
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}
