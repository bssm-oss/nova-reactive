<!--
 Licensed under the Apache License, Version 2.0 (the "License");
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at

   http://www.apache.org/licenses/LICENSE-2.0
-->

<!-- START Nova, please keep comment here to allow auto update of readme.md -->
# Nova

| Category   | Badges                                                                                                                                                                                                                                                                  |
|------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| License    | [![License](https://img.shields.io/:license-Apache%202-blue.svg)](https://www.apache.org/licenses/LICENSE-2.0.txt)                                                                                                                                                        |
| Build      | [![Java](https://img.shields.io/badge/Java-21-007396?logo=openjdk&logoColor=white)](https://openjdk.org/projects/jdk/21/) [![Gradle](https://img.shields.io/badge/Gradle-8.10+-02303A?logo=gradle&logoColor=white)](https://gradle.org/) [![Kotlin DSL](https://img.shields.io/badge/Kotlin_DSL-build_scripts-7F52FF?logo=kotlin&logoColor=white)](https://docs.gradle.org/current/userguide/kotlin_dsl.html) |
| Stack      | [![Reactor](https://img.shields.io/badge/Project_Reactor-3.7-6db33f?logo=spring&logoColor=white)](https://projectreactor.io/) [![R2DBC](https://img.shields.io/badge/R2DBC-1.0-4479A1?logo=databricks&logoColor=white)](https://r2dbc.io/)                                |
| Databases  | [![PostgreSQL](https://img.shields.io/badge/PostgreSQL-supported-336791?logo=postgresql&logoColor=white)](https://www.postgresql.org/) [![MySQL](https://img.shields.io/badge/MySQL-supported-4479A1?logo=mysql&logoColor=white)](https://www.mysql.com/)                 |
| Status     | ![Status](https://img.shields.io/badge/status-alpha-orange)                                                                                                                                                                                                              |

<p align="center">
  <b>Lightweight Reactive ORM for Java 21</b><br/>
  <i>JPA-style annotations · R2DBC-native · Pluggable dialects</i>
</p>

<!-- END Nova, please keep comment here to allow auto update of readme.md -->

**Nova** is a lightweight **reactive ORM** built on R2DBC and Project Reactor.
It keeps the JPA-style annotation model that Java developers already know
while exposing every persistence API as `Mono` / `Flux`, so it fits naturally
into non-blocking data pipelines. Nova avoids the persistence-context,
first-level-cache, and lazy-loading complexity of full-stack ORMs, and the
core module depends only on the R2DBC SPI.

> **Nova is not** a streaming framework, a query builder for arbitrary SQL,
> or a drop-in replacement for JPA. The goal is a small-surface-area reactive
> data-access layer.

---

## Quick start

```kotlin
// build.gradle.kts
repositories { mavenCentral() }

dependencies {
    implementation("io.github.bssm-oss:nova:1.0.1")
    runtimeOnly("io.r2dbc:r2dbc-h2:1.0.0.RELEASE")
    // runtimeOnly("org.postgresql:r2dbc-postgresql:1.0.7.RELEASE")
}
```

```java
import io.nova.Nova;
import io.nova.core.ReactiveEntityOperations;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;

@Entity
@Table(name = "accounts")
public class Account {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "email_address") private String email;
    public Account() {}
    public Account(Long id, String email) { this.id = id; this.email = email; }
    // getters/setters...
}

ConnectionFactory cf = ConnectionFactories.get(
        "r2dbc:h2:mem:///nova-smoke?options=DB_CLOSE_DELAY=-1");
ReactiveEntityOperations operations = Nova.create(cf);

Nova.schemaInitializer(cf).create(Account.class)
    .then(operations.save(new Account(null, "user@example.com")))
    .flatMap(saved -> operations.findById(Account.class, saved.getId()))
    .subscribe(System.out::println);
```

`Nova.create(cf)` and `Nova.schemaInitializer(cf)` auto-detect the dialect (PostgreSQL / MySQL / MariaDB / H2 / Oracle) from the R2DBC driver metadata. In Spring Boot, the starter wires both beans automatically and supports JPA-style `nova.ddl-auto=create-drop`. See [Getting started](docs/getting-started.md) for details.

---

## Documentation

| Document                                       | Contents                                                                  |
|------------------------------------------------|---------------------------------------------------------------------------|
| [Getting started](docs/getting-started.md)     | Installation, first entity, `Nova.create(...)`, schema initialization      |
| [Entities](docs/entities.md)                   | Annotations, composite types, relationships, indexes                       |
| [Queries](docs/queries.md)                     | CRUD, Query DSL, Updater, Projection, Aggregations, Page/Slice, Cursor     |
| [Transactions](docs/transactions.md)           | Propagation / isolation, pessimistic locking, retry                        |
| [Dialects & Schema](docs/dialects.md)          | `Dialect` SPI, the five bundled dialects, `SchemaGenerator`, migration     |
| [Spring](docs/spring.md)                       | Spring Boot starter, `nova-spring-data` repositories                       |
| [Observability](docs/observability.md)         | `SqlExecutionListener`, Micrometer adapter, pool reachability              |

Full documentation index: [`docs/`](docs/README.md).

---

## Requirements

|                  | Version                         | Notes                              |
|------------------|---------------------------------|------------------------------------|
| Java             | 21                              | Resolved by Gradle Toolchains      |
| Build            | Gradle 8.10+                    | Wrapper (`./gradlew`) included     |
| Reactive Runtime | Project Reactor 3.7.x           |                                    |
| Driver SPI       | R2DBC SPI 1.0.0.RELEASE         |                                    |
| PostgreSQL       | 14, 15, 16, 17                  | `nova-dialect-postgresql`          |
| MySQL            | 8.0, 8.4                        | `nova-dialect-mysql`               |
| MariaDB          | 10.5+, 11                       | `nova-dialect-mariadb`             |
| H2               | 2.x                             | `nova-dialect-h2`                  |
| Oracle           | 12c+, 19c, 21c+                 | `nova-dialect-oracle`              |

Nova core depends only on the R2DBC **SPI**. Add the matching R2DBC driver (`r2dbc-postgresql`, `r2dbc-mysql`, etc.) as a separate runtime dependency.

---

## Modules

| Module                       | Description                                                                  |
|------------------------------|------------------------------------------------------------------------------|
| `nova`                       | aggregate — core + r2dbc + every bundled dialect                              |
| `nova-core`                  | Annotations, metadata, query DSL, SQL rendering, transactions, `ReactiveEntityOperations`, and other core abstractions |
| `nova-r2dbc`                 | R2DBC SPI adapter — `R2dbcSqlExecutor`, `R2dbcTransactionManager`, `SqlExecutionListener` hooks |
| `nova-dialect-postgresql`    | PostgreSQL dialect (`$N` bind marker, `bigserial`, `RETURNING`)                |
| `nova-dialect-mysql`         | MySQL dialect (`?` bind marker, `auto_increment`)                              |
| `nova-dialect-h2`            | H2 dialect (`GENERATED ALWAYS AS IDENTITY`)                                    |
| `nova-dialect-mariadb`       | MariaDB dialect                                                                |
| `nova-dialect-oracle`        | Oracle dialect (`OFFSET..FETCH`, `<seq>.nextval from dual`)                    |
| `nova-spring-boot-starter`   | Spring Boot auto-configuration — dialect auto-detect, `nova.*` properties     |
| `nova-spring-data`           | Spring Data-style `ReactiveCrudRepository<T, ID>` + `@EnableNovaRepositories` |
| `nova-metrics-micrometer`    | Micrometer adapter (`MicrometerSqlExecutionListener`)                          |

Maven coordinates stay flat under `io.github.bssm-oss:<module>:1.0.1`.

---

## Building from source

```bash
./gradlew build          # full build + tests
./gradlew test           # tests only
./gradlew clean build    # clean rebuild
```

The Gradle Wrapper (`./gradlew`) is bundled — no separate Gradle install is required.

---

## Roadmap

- [x] Optimistic locking (`@Version`) — Long / Integer / Short, surfaces `OptimisticLockingFailureException` on conflict
- [x] Soft delete (`@SoftDelete`) — rewrites DELETE as UPDATE; SELECT gets an automatic alive guard
- [x] Audit fields (`@CreatedAt` / `@UpdatedAt`) — injectable `Clock`
- [x] Entity lifecycle callbacks (`@PrePersist` / `@PostPersist` / `@PreUpdate` / `@PostUpdate` / `@PostLoad` / `@PreRemove` / `@PostRemove`)
- [x] Updater builder DSL — criteria-based partial UPDATE without an entity instance
- [x] Projections (record / DTO mapping)
- [x] Aggregations (`count` / `countDistinct` / `sum` / `avg` / `min` / `max` + `groupBy` + `having`)
- [x] Cursor / keyset pagination
- [x] NativeQuery — raw SQL entry point
- [x] CompiledQuery — render SQL once, swap bindings
- [x] SQL execution hook (`SqlExecutionListener`, `SlowQueryLoggingListener`)
- [x] Transaction propagation / isolation / readOnly hints
- [x] SEQUENCE / UUID id generation
- [x] Batch insert with generated-id recovery
- [x] `@Embeddable` / `@Embedded` composite value types
- [x] Table-level `@Index` / `@UniqueConstraint`
- [x] Schema migration helpers (`createIndexes`, `alterTableAddColumn`, `alterTableDropColumn`)
- [x] H2 / MariaDB / Oracle dialects
- [x] Spring Boot auto-configuration (`nova-spring-boot-starter`) with dialect auto-detect
- [x] Pessimistic locking (`QuerySpec.forUpdate()` / `forShare()`)
- [x] Metrics adapter (`nova-metrics-micrometer` — Micrometer)
- [x] Retry helper (`ReactiveRetryTemplate` — exponential backoff + jitter)
- [x] FetchGroup DSL + relationship mapping (`@ManyToOne` / `@OneToMany` automatic hydration)
- [x] `Page` / `Slice` result types + `PageRequest` page-number-friendly API
- [x] Spring Data-style repositories (`nova-spring-data`, no dependency on Spring Data Commons)
- [x] JSON column type (`@Json` — pluggable `JsonCodec` SPI)
- [x] `@Column(length / precision / scale)` and `BigDecimal` columns
- [x] 1.0 GA released to Maven Central (`io.github.bssm-oss:nova:1.0.0` — all 11 modules published)

For in-flight and proposed items, see the issue tracker.

---

## Contributing

Contributions are welcome.

1. Open an issue first to agree on scope and direction.
2. Branch off `main` and keep PRs **small** and focused on a single concern.
3. Run `./gradlew build` and confirm every test passes.
4. Follow [Conventional Commits](https://www.conventionalcommits.org/) for commit messages.

The full guide — issue / PR / commit conventions and merge policy — is in [CONTRIBUTING.md](CONTRIBUTING.md).

---

## License

Nova is released under the [Apache License 2.0](LICENSE).

```
Copyright (c) Nova contributors
Licensed under the Apache License, Version 2.0
```
