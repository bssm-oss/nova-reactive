<!-- SPDX-License-Identifier: Apache-2.0 -->

# Spring Boot & Spring Data

## Spring Boot starter

Adding `nova-spring-boot-starter` registers every core bean via `NovaAutoConfiguration`. User-defined beans are guarded with `@ConditionalOnMissingBean` and are never overridden.

```kotlin
// build.gradle.kts
dependencies {
    implementation("io.github.bssm-oss:nova-spring-boot-starter:2.37.0")
    implementation("io.github.bssm-oss:nova-dialect-postgresql:2.37.0")
    runtimeOnly("org.postgresql:r2dbc-postgresql:1.0.7.RELEASE")
}
```

`NovaAutoConfiguration` activates once the `ConnectionFactory` and `Dialect` classes are on the classpath (always true once a dialect module is added). It requires a `ConnectionFactory` bean in the context — if none is supplied, context startup fails with an unsatisfied-dependency error rather than the starter silently backing off. `Dialect` is auto-detected from that `ConnectionFactory`'s driver metadata (see `Nova.resolveDialect`); supply your own `Dialect` bean only to override auto-detection or to support an unmapped driver. Once wired, the starter registers the following beans (each only when missing):

| Bean                          | Type                                | Notes                                                                  |
|-------------------------------|-------------------------------------|------------------------------------------------------------------------|
| `novaNamingStrategy`          | `DefaultNamingStrategy`             | Class → snake_case conversion                                            |
| `novaEntityMetadataFactory`   | `EntityMetadataFactory`             | Caches entity metadata                                                   |
| `novaEntityStateDetector`     | `EntityStateDetector`               | Decides insert vs update based on the identifier                         |
| `novaTransactionManager`      | `R2dbcTransactionManager`           | Tx propagation via Reactor Context                                       |
| `novaSqlExecutor`             | `R2dbcSqlExecutor`                  | Composes every `SqlExecutionListener` bean into a `CompositeSqlExecutionListener` automatically |
| `novaEntityOperations`        | `SimpleReactiveEntityOperations`    | The user-facing entry point                                              |
| `novaPoolConfig`              | `PoolConfig`                        | Always exposed; unspecified fields fall back to `PoolConfig.defaults()`  |
| `novaSlowQueryLoggingListener`| `SlowQueryLoggingListener`          | Registered only when `nova.slow-query.threshold-ms` is set               |
| `novaSchemaInitializer`       | `SchemaInitializer`                 | Always exposed — call `schemaInitializer.create(MyEntity.class)` from anywhere |
| `novaSchemaBootstrapRunner*`  | `SchemaBootstrapRunner`             | Registered when `nova.ddl-auto` is `update`, `create`, `create-drop`, or `validate` |

Add a `SqlExecutionListener` bean (e.g. `MicrometerSqlExecutionListener`) to the context and it is automatically composed into the executor.

### Reactive `@Transactional`

The starter includes Spring R2DBC integration, so the standard Spring annotation works on
`Mono`- and `Flux`-returning service methods. Spring Boot auto-configures an
`org.springframework.r2dbc.connection.R2dbcTransactionManager`, and Nova automatically borrows
its Reactor-context-bound connection for every operation in the publisher.

```java
@Service
class CheckoutService {
    private final OrderRepository orders;
    private final PaymentRepository payments;

    CheckoutService(OrderRepository orders, PaymentRepository payments) {
        this.orders = orders;
        this.payments = payments;
    }

    @Transactional
    public Mono<Order> checkout(Order order, Payment payment) {
        return orders.save(order)
                .flatMap(saved -> payments.save(payment).thenReturn(saved));
    }
}
```

The transaction starts on subscription, commits after successful publisher completion, and rolls
back on an error signal or cancellation. Spring propagation, isolation, timeout, and read-only
attributes keep their normal reactive R2DBC semantics. The usual Spring proxy rules also apply:
self-invocation does not cross the transactional proxy, and the annotated method must expose a
reactive return type rather than calling `block()`.

This annotation provides a Spring-managed atomic database boundary. Nova's transaction-bound
persistence session (identity map, snapshot dirty checking, and commit-time flush) remains the
scope of `ReactiveEntityOperations.inTransaction(...)`; use that API when those unit-of-work
semantics are required in addition to atomic statements.

The starter also registers `novaEntityPreloadRunner`, which at startup scans
`nova.entity-packages` (or the auto-configuration packages) for both `@Entity` and Jakarta
`@Converter` classes — regardless of `nova.ddl-auto`. It registers every discovered converter
before eagerly building metadata for every discovered entity, so `autoApply` conversion is
deterministic. This mirrors a JPA persistence unit knowing all of its entities up front, and is
what lets `SINGLE_TABLE` inheritance dispatch a polymorphic `findAll(Vehicle.class)` to the
right concrete subtypes. Entity metadata build errors surface at startup (fail-fast) rather than
on first query. Only schema creation or validation is conditional on `nova.ddl-auto`.
The auto-configured `ReactiveEntityOperations` bean explicitly initializes this preloader first,
so an auto-discovered repository cannot request derived-query metadata before converter discovery
completes. A user-provided `NovaEntityPreloadRunner` is honored by type even when it uses a custom
bean name.

### Schema bootstrap (`nova.ddl-auto`)

The starter mirrors JPA's `spring.jpa.hibernate.ddl-auto`, so the same value set binds. A `SchemaBootstrapRunner` runs synchronously during its own context-refresh initialization via `InitializingBean#afterPropertiesSet()` and scans the configured packages for `@jakarta.persistence.Entity` classes. Spring does not globally order unrelated beans' initialization, so a bean that queries the database from its own initialization callback must explicitly be ordered after the active `SchemaBootstrapRunner` when schema bootstrap must finish first.

| `nova.ddl-auto` | Behavior |
|-----------------|----------|
| `none` (default) | Do nothing. |
| `update` | `CREATE TABLE IF NOT EXISTS` (plus indexes) — creates missing tables only, never drops. Unlike Hibernate, Nova does not `ALTER` existing tables to add missing columns. |
| `create` | Drop the tables (if any) and recreate them — destructive, matching Hibernate's `create`. |
| `create-drop` | Like `create`, and also `DROP TABLE IF EXISTS` in reverse order on context close via `DisposableBean#destroy()` (FK-friendly). |
| `validate` | Uses the dialect's catalog queries (for example, `information_schema.tables` / `.columns`) to check ordinary entity tables and inheritance-root metadata, and **fails startup** with the collected missing-table/column problems. |

Validation collapses inheritance hierarchies to their roots. It checks an ordinary entity's
primary table columns plus its secondary tables and their mapped columns. For every inheritance
strategy, it instead checks the collapsed root's primary table metadata, including its mapped
columns and configured discriminator, plus secondary tables present on that root metadata. It
does not validate `JOINED` / `TABLE_PER_CLASS` subtype physical tables, subtype-only secondary
tables, generator tables, join tables, collection tables, order columns, indexes, constraints, or
column types. In particular, `TABLE_PER_CLASS` validation still targets the root table and
discriminator even though schema creation emits subtype tables, so `validate` is not a complete
or appropriate check for a `TABLE_PER_CLASS` physical schema. Use a migration tool for complete
schema validation.

Production deployments should keep the default of `none` and manage schema with a real migration tool such as Flyway or Liquibase.

```yaml
nova:
  ddl-auto: create-drop          # none | update | create | create-drop | validate
  entity-packages:               # optional; falls back to @SpringBootApplication's package
    - com.example.domain
    - com.example.billing.domain
```

### Auto-configuration properties

| Property                          | Type            | Default                       | Description                                          |
|-----------------------------------|-----------------|-------------------------------|------------------------------------------------------|
| `nova.pool.initial-size`          | `Integer`       | `PoolConfig.defaults()` value | Initial connection count                              |
| `nova.pool.max-size`              | `Integer`       | `PoolConfig.defaults()` value | Maximum connection count                              |
| `nova.pool.max-idle-time`         | `Duration`      | `PoolConfig.defaults()` value | Idle-connection expiration                            |
| `nova.pool.acquire-timeout`       | `Duration`      | `PoolConfig.defaults()` value | Acquire wait timeout                                  |
| `nova.slow-query.threshold-ms`    | `Long`          | (unset)                       | When set, registers `SlowQueryLoggingListener`         |
| `nova.ddl-auto`                   | `DdlAuto`       | `none`                        | `none` / `update` / `create` / `create-drop` / `validate` schema lifecycle |
| `nova.entity-packages`            | `List<String>`  | (empty → AutoConfigurationPackages) | Startup packages for managed `@Entity` and Jakarta `@Converter` discovery; schema creation still depends on `ddl-auto` |
| `nova.repositories.enabled`       | `boolean`       | `true`                        | Discover `ReactiveCrudRepository` interfaces below the Spring Boot application packages |

> The starter only exposes a `PoolConfig` bean; it does not bundle a pool implementation such as `r2dbc-pool`. If you need pooling, add the dependency yourself and feed this `PoolConfig` into your `ConnectionFactory` bean.

---

## Spring Data-style repositories (`nova-spring-data`)

The starter includes `nova-spring-data` transitively and discovers repository interfaces below
the package of `@SpringBootApplication`, matching Spring Boot's normal repository experience.
Define the interface; no repository configuration annotation is required:

```java
import io.nova.spring.data.ReactiveCrudRepository;

public interface AuthorRepository extends ReactiveCrudRepository<Author, Long> {
}
```

When using `nova-spring-data` without the starter, add it directly and enable the packages to scan.
Its normal repository API exports Spring Framework's `spring-context` and does not add Spring Data
Commons transitively. The module is compiled against Spring Data Commons only for an optional
standard `Pageable` / `Sort` / `Page` / `Slice` bridge.

```kotlin
dependencies {
    implementation("io.github.bssm-oss:nova-spring-data:2.37.0")

    // Only when using SpringDataReactiveCrudRepository or the standard bridge helpers:
    implementation("org.springframework.data:spring-data-commons:3.4.5")
}
```

```java
@Configuration
@EnableNovaRepositories(basePackages = "com.example.author")
class AppConfig {}
```

An explicit `@EnableNovaRepositories` declaration disables the starter's default repository scan
and becomes the sole repository configuration. Use it when repositories live outside the Boot
application packages or when selecting custom `entityOperationsRef`, `dialectRef`, or
`entityMetadataFactoryRef` beans. Set `nova.repositories.enabled=false` to disable automatic
repository discovery without adding an explicit configuration.

Extend `SpringDataReactiveCrudRepository<T, ID>` instead when repository methods should use
`org.springframework.data.domain.Pageable`, `Sort`, `Page`, or `Slice`. Those standard types
require `spring-data-commons` on the consumer's runtime classpath. Repositories that extend the
base `ReactiveCrudRepository` keep using Nova's own paging and sorting types and do not require it.

Both automatic and explicit discovery register a JDK proxy + `NovaRepositoryFactoryBean` for every
discovered interface. Every method delegates to `ReactiveEntityOperations` (the
`novaEntityOperations` bean by default). Methods provided:

```java
Mono<T> save(T entity);
Flux<T> saveAll(Iterable<T> entities);
Mono<T> findById(ID id);
Mono<Boolean> existsById(ID id);
Flux<T> findAll();
Flux<T> findAll(QuerySpec spec);
Flux<T> findAll(Pageable pageable);
Mono<Page<T>> findAll(QuerySpec spec, Pageable pageable);
Flux<T> findAllById(Iterable<ID> ids);
Mono<Long> count();
Mono<Long> deleteById(ID id);
Mono<Long> delete(T entity);
Mono<Long> deleteAll(Iterable<T> entities);
```

### Annotated queries

JPQL-backed and native entity `@Query` methods returning `Mono<T>` are zero-or-one
queries: zero rows complete empty, one row is emitted, and multiple rows fail with
`JpqlException` for JPQL or `AnnotatedQueryException` for native SQL. This does not
truncate results. Derived `findFirst` and `findTop` methods, and an explicit SQL
`LIMIT`, are distinct opt-in limiting operations.

Native `@Query(nativeQuery = true)` supports entity `SELECT` statements that select all
entity columns and `@Modifying` bulk `UPDATE`, `DELETE`, and `INSERT` statements.
Native scalar and constructor projections, and native queries with `Pageable`, fail fast;
use JPQL for those query shapes.

### Derived query methods

For familiarity with Spring Data, the proxy also parses method names that follow a `find / findFirst / count / exists / delete` convention. Anything the fixed-name switch above does not match falls through to the derived query parser; if that succeeds, it dispatches to `ReactiveEntityOperations`. If neither matches, the call returns `Mono.error(UnsupportedOperationException)`.

```java
public interface AuthorRepository extends ReactiveCrudRepository<Author, Long> {
    Mono<Author>  findByEmail(String email);                 // Mono → LIMIT 1
    Flux<Author>  findByActiveTrue();                        // 0-arg keyword
    Mono<Long>    countByActive(boolean active);
    Mono<Boolean> existsByEmail(String email);
    Mono<Long>    deleteByActiveFalse();
    Flux<Author>  findByEmailIn(Iterable<String> emails);
    Flux<Author>  findByCreatedAtAfter(Instant after);       // After / Before alias for Gt / Lt
    Flux<Author>  findByEmailAndActiveTrueOrderByCreatedAtDesc(String email);
    Mono<Author>  findFirstByActiveTrueOrderByCreatedAtDesc();
}
```

**Subjects** — `find` (Mono = LIMIT 1, Flux = all), `findFirst`/`findTop`/`findOne` (always Mono with LIMIT 1), `count` (`Mono<Long>`), `exists` (`Mono<Boolean>`), `delete`/`remove` (`Mono<Long>`). An explicit count prefix `findTop<N>By` / `findFirst<N>By` (N ≥ 2) returns a `Flux` capped at LIMIT N; `findTop1By`/`findFirst1By` collapse to the single-result `Mono` form.

**Keywords** — default (equality), `Not`, `LessThan`/`Lt` (alias `Before`), `LessThanEqual`/`Lte`, `GreaterThan`/`Gt` (alias `After`), `GreaterThanEqual`/`Gte`, `Like`, `StartingWith`/`StartsWith`, `EndingWith`/`EndsWith`, `Containing`/`Contains`, `In`, `NotIn`, `Between` (consumes two parameters), `IsNull` / `Null`, `IsNotNull` / `NotNull`, `True` / `IsTrue`, `False` / `IsFalse`.

**Case-insensitive** — an `IgnoreCase` suffix on a string comparison (`findByEmailIgnoreCase`, `findByNameStartingWithIgnoreCase`, also `Containing`/`EndingWith`/equality) matches case-insensitively. Applying it to a non-string keyword (`GreaterThanIgnoreCase`, `BetweenIgnoreCase`, `IsNullIgnoreCase`) fails fast at parse time.

**Connectors** — `And` / `Or` (left-to-right; no precedence — parenthesisation matches Spring Data conventions).

**Sort** — `OrderBy<Property>(Asc|Desc)?(And<Property>(Asc|Desc)?)*` appended after the predicate clause.

**Property resolution** — greedy match against the entity's logical properties. Longer property names win to avoid prefix ambiguity. Method names use lowerCamelCase form (`findByEmailAddress` → property `emailAddress`). Embedded paths retain their dotted canonical name and can use a concatenated token (`findByAddressCity`) when unambiguous, or an explicit underscore traversal token (`findByAddress_City` → `address.city`; deeper paths use one underscore per segment). When a direct property and a concatenated embedded path share a token, the direct property wins; direct property underscores remain literal (`findByAddress_city` → `address_city`).

**Paging** — a `find…By` method may take a trailing `Pageable` parameter. The return-type shape selects the container: `Flux<T>` streams a single LIMIT/OFFSET window (no total, no `hasNext`); `Mono<Page<T>>` adds a separate `COUNT(*)` for `totalElements`; `Mono<Slice<T>>` fetches `limit + 1` to decide `hasNext` without a count query.

```java
Flux<Author>        findByActiveTrue(Pageable page);          // one window, streamed
Mono<Page<Author>>  findByActiveTrue(Pageable page);          // window + total count
Mono<Slice<Author>> findByActiveTrue(Pageable page);          // window + hasNext, no count
```

A `Pageable` parameter is only valid on the `find`-all subject. Pairing it with a non-paging shape or subject — `count`/`exists`/`delete`, or a single-result `Mono<T>` / `findFirst` / `findOne` / `findTop` / `findTop<N>` — fails fast at parse time with an `IllegalArgumentException`.

**Limitations** — `@Embedded` paths in projections are unsupported. Native `@Query` supports
entity `SELECT` statements that select all entity columns and `@Modifying` bulk `UPDATE`,
`DELETE`, and `INSERT`; native scalar and constructor projections, and native
`Pageable`/`Page`/`Slice` query shapes, fail fast. Use JPQL for unsupported projection and
paging shapes, or `findAll(QuerySpec)` (with [`nova-metamodel`](metamodel.md)'s generated
property-name constants where useful).

Misuse — unknown property, parameter-count mismatch, unrecognized keyword suffix — fails at the first call to that method with an `IllegalArgumentException` carrying a precise diagnostic. Method names whose subject prefix does not match (`saveAndPublish`, `magicMethod`, …) fall through to the existing `UnsupportedOperationException` as before.
