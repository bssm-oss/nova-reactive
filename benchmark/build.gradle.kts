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
    // HikariCP integration — proper connection queueing under concurrency >> pool size
    // (the built-in pool throws instead of queueing). Used for both backends, capped at 20.
    implementation("org.hibernate.orm:hibernate-hikaricp:7.0.0.Final")
    // JDBC H2 driver for Hibernate's blocking path.
    implementation("com.h2database:h2:2.3.232")

    // PostgreSQL backend (Testcontainers) — real socket round-trips so reactive concurrency
    // behaviour is observable, unlike H2 in-memory (zero I/O wait).
    implementation("org.testcontainers:postgresql:1.20.4")
    implementation("org.postgresql:postgresql:42.7.4")          // JDBC, Hibernate ORM (blocking)
    implementation("org.postgresql:r2dbc-postgresql:1.0.7.RELEASE") // R2DBC, Nova

    // Hibernate Reactive — non-blocking Hibernate on Vert.x (true reactive-vs-reactive vs Nova).
    // Pairs with Hibernate ORM 7 (Jakarta 3.2). Vert.x PG client is the reactive driver; PG-only.
    implementation("org.hibernate.reactive:hibernate-reactive-core:3.0.0.Final")
    implementation("io.vertx:vertx-pg-client:4.5.11")

    // Reactor (transitive via Nova, declared explicitly for clarity in the harness).
    implementation("io.projectreactor:reactor-core:3.7.3")
    // SLF4J binding so Testcontainers/Hibernate diagnostics are visible (WARN+).
    runtimeOnly("org.slf4j:slf4j-simple:2.0.16")
}

application {
    mainClass = "io.nova.benchmark.BenchmarkRunner"
}

// Testcontainers는 Docker 소켓을 찾아야 한다. Docker Desktop(macOS)의 소켓은 기본 경로가 아니라
// ~/.docker/run/docker.sock이므로, DOCKER_HOST가 없으면 그 경로를 run 태스크 JVM 환경에 주입한다.
tasks.named<JavaExec>("run") {
    val dockerHost = System.getenv("DOCKER_HOST")
        ?: "unix://${System.getProperty("user.home")}/.docker/run/docker.sock"
    environment("DOCKER_HOST", dockerHost)
    environment("TESTCONTAINERS_RYUK_DISABLED", System.getenv("TESTCONTAINERS_RYUK_DISABLED") ?: "true")
    // Docker Desktop 29.x는 매우 최신이라 docker-java의 API 버전 협상이 400으로 실패한다 — 호환 버전 고정.
    environment("DOCKER_API_VERSION", System.getenv("DOCKER_API_VERSION") ?: "1.43")
    // -Dbench.* / -Dorg.slf4j.* 시스템 프로퍼티를 application JVM으로 전달한다(gradle run은 기본 미전달).
    System.getProperties().forEach { key, value ->
        val name = key.toString()
        if (name.startsWith("bench.") || name.startsWith("org.slf4j.")) {
            systemProperty(name, value.toString())
        }
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}
