<!-- SPDX-License-Identifier: Apache-2.0 -->

# Getting started

Nova `1.0.1` is available from Maven Central (the latest release after the `1.0.0` GA).

## 1. Add dependencies

The fastest path is the aggregate module `io.github.bssm-oss:nova` plus an R2DBC driver for the database you target. The aggregate pulls in core, the R2DBC adapter, and every bundled dialect (PostgreSQL / MySQL / MariaDB / H2 / Oracle).

```kotlin
// build.gradle.kts
repositories {
    mavenCentral()
}

dependencies {
    implementation("io.github.bssm-oss:nova:1.0.1")

    // The R2DBC driver for your database (pick one)
    runtimeOnly("io.r2dbc:r2dbc-h2:1.0.0.RELEASE")
    // runtimeOnly("org.postgresql:r2dbc-postgresql:1.0.7.RELEASE")
    // runtimeOnly("io.asyncer:r2dbc-mysql:1.3.0")
}
```

To pull in only a specific dialect instead of the aggregate, depend on `nova-core` + `nova-r2dbc` + the dialect module directly:

```kotlin
// build.gradle.kts
dependencies {
    implementation("io.github.bssm-oss:nova-core:1.0.1")
    implementation("io.github.bssm-oss:nova-r2dbc:1.0.1")
    implementation("io.github.bssm-oss:nova-dialect-postgresql:1.0.1")
    runtimeOnly("org.postgresql:r2dbc-postgresql:1.0.7.RELEASE")
}
```

Groovy DSL:

```groovy
// build.gradle
dependencies {
    implementation 'io.github.bssm-oss:nova:1.0.1'
    runtimeOnly 'io.r2dbc:r2dbc-h2:1.0.0.RELEASE'
}
```

> **Snapshots**: next-dev builds (e.g. `1.0.2-SNAPSHOT`) are available from the Central snapshots repository.
>
> ```kotlin
> repositories {
>     mavenCentral()
>     maven("https://central.sonatype.com/repository/maven-snapshots/")
> }
> ```

## 2. Define an entity

```java
@Entity
@Table("accounts")
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column("email_address")
    private String email;

    @Column(nullable = false)
    private boolean active;

    public Account() {}

    public Account(Long id, String email, boolean active) {
        this.id = id;
        this.email = email;
        this.active = active;
    }

    public Long getId() { return id; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
}
```

For the full annotation reference, see [Entities](entities.md).

## 3. Run operations

`Nova.create(connectionFactory)` reads the R2DBC driver metadata to auto-detect the dialect (PostgreSQL / MySQL / MariaDB / H2 / Oracle) and assembles a `ReactiveEntityOperations`. For drivers that are not auto-mapped, inject a dialect explicitly with `Nova.create(cf, dialect)`.

```java
import io.nova.Nova;
import io.nova.core.ReactiveEntityOperations;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;

ConnectionFactory cf = ConnectionFactories.get(
        "r2dbc:h2:mem:///nova-smoke?options=DB_CLOSE_DELAY=-1");
ReactiveEntityOperations operations = Nova.create(cf);

operations.save(new Account(null, "user@example.com", true))
          .flatMap(saved -> operations.findById(Account.class, saved.getId()))
          .subscribe(System.out::println);
```

## 4. Initialize the schema (optional)

In production, prefer a migration tool such as Flyway or Liquibase. For integration tests and demo seeding, `Nova.schemaInitializer(cf)` returns a one-liner that auto-detects the dialect and issues idempotent DDL:

```java
import io.nova.Nova;
import io.nova.schema.SchemaInitializer;

SchemaInitializer schema = Nova.schemaInitializer(cf);

schema.create(Account.class)
      .then(operations.save(new Account(null, "user@example.com", true)))
      .block();
```

By default, statements are emitted as `CREATE TABLE IF NOT EXISTS` so re-running the bootstrap is safe. Pass `SchemaOptions.defaults().withIfNotExists(false)` to force a raw `CREATE TABLE` instead.

Batch and lifecycle variants:

```java
schema.create(Author.class, Book.class);        // emits parent then child
schema.drop(Book.class, Author.class);          // drops child then parent
schema.recreate(Author.class, Book.class);      // drop + recreate, FK-safe ordering
```

For lower-level control, the raw `dialect.schemaGenerator()` DDL strings stay available — see [Dialects & Schema](dialects.md).

## With Spring Boot

In a Spring Boot application, the [`nova-spring-boot-starter`](spring.md) reads your `ConnectionFactory` bean, auto-detects the dialect, and registers a `ReactiveEntityOperations` bean. No explicit `Nova.create(...)` call is needed.

The starter also exposes a `SchemaInitializer` bean and supports JPA-style `nova.ddl-auto` configuration. Set `nova.ddl-auto=create` (or `create-drop`) and the starter scans for `@Entity` classes and provisions the schema on startup — perfect for integration tests:

```yaml
nova:
  ddl-auto: create-drop
  entity-packages: com.example.domain   # optional; defaults to @SpringBootApplication's package
```

See [Spring](spring.md) for the full property reference.

## Next steps

- [Entities](entities.md) — annotations, relationship mapping, composite types, indexes
- [Queries](queries.md) — Query DSL, Updater, Projection, Aggregations, pagination
- [Transactions](transactions.md) — propagation, pessimistic locking, retry
- [Spring](spring.md) — Spring Boot starter, Spring Data-style repositories
