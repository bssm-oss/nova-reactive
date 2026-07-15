<!-- SPDX-License-Identifier: Apache-2.0 -->

# Spring Boot & Spring Data

## Spring Boot starter

Adding `nova-spring-boot-starter` registers every core bean via `NovaAutoConfiguration`. User-defined beans are guarded with `@ConditionalOnMissingBean` and are never overridden.

```kotlin
// build.gradle.kts
dependencies {
    implementation("io.github.bssm-oss:nova-spring-boot-starter:2.8.0")
    implementation("io.github.bssm-oss:nova-dialect-postgresql:2.8.0")
    runtimeOnly("org.postgresql:r2dbc-postgresql:1.0.7.RELEASE")
}
```

Activated when both `ConnectionFactory` and `Dialect` beans are present in the context, the starter registers the following beans (each only when missing):

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

The starter also registers `novaEntityPreloadRunner`, which eagerly builds metadata for every `@Entity` in `nova.entity-packages` (or the auto-configuration packages) at startup — regardless of `nova.ddl-auto`. This mirrors a JPA persistence unit knowing all of its entities up front, and is what lets `SINGLE_TABLE` inheritance dispatch a polymorphic `findAll(Vehicle.class)` to the right concrete subtypes. Entity metadata build errors surface at startup (fail-fast) rather than on first query.

### Schema bootstrap (`nova.ddl-auto`)

The starter mirrors JPA's `spring.jpa.hibernate.ddl-auto`, so the same value set binds. A `SchemaBootstrapRunner` runs during context refresh via `InitializingBean#afterPropertiesSet()` (so the schema is ready before any other refresh-time bean queries it) and scans the configured packages for `@jakarta.persistence.Entity` classes.

| `nova.ddl-auto` | Behavior |
|-----------------|----------|
| `none` (default) | Do nothing. |
| `update` | `CREATE TABLE IF NOT EXISTS` (plus indexes) — creates missing tables only, never drops. Unlike Hibernate, Nova does not `ALTER` existing tables to add missing columns. |
| `create` | Drop the tables (if any) and recreate them — destructive, matching Hibernate's `create`. |
| `create-drop` | Like `create`, and also `DROP TABLE IF EXISTS` in reverse order on context close via `DisposableBean#destroy()` (FK-friendly). |
| `validate` | Checks that a table **and all mapped columns** exist for every entity (via the dialect's catalog queries, e.g. `information_schema.tables` / `.columns`); **fails startup** listing any missing tables/columns. Column types are not compared. |

Production deployments should keep the default of `none` and manage schema with a real migration tool such as Flyway or Liquibase.

```yaml
nova:
  ddl-auto: create-drop          # none | create | create-drop
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
| `nova.ddl-auto`                   | `DdlAuto`       | `none`                        | `none` / `create` / `create-drop` schema bootstrap     |
| `nova.entity-packages`            | `List<String>`  | (empty → AutoConfigurationPackages) | Packages to scan for `@Entity` when `ddl-auto` runs |

> The starter only exposes a `PoolConfig` bean; it does not bundle a pool implementation such as `r2dbc-pool`. If you need pooling, add the dependency yourself and feed this `PoolConfig` into your `ConnectionFactory` bean.

---

## Spring Data-style repositories (`nova-spring-data`)

The familiar `interface ... extends ReactiveCrudRepository<T, ID>` pattern is available as a separate dependency (`io.github.bssm-oss:nova-spring-data:2.8.0`). It depends only on Spring Framework's `spring-context` — not on Spring Data Commons.

```java
import io.nova.spring.data.ReactiveCrudRepository;

public interface AuthorRepository extends ReactiveCrudRepository<Author, Long> {
}

@Configuration
@EnableNovaRepositories(basePackages = "com.example.author")
class AppConfig {}
```

`@EnableNovaRepositories` scans the base packages and registers a JDK proxy + `NovaRepositoryFactoryBean` for every discovered interface. Every method delegates to `ReactiveEntityOperations` (the `novaEntityOperations` bean). Methods provided:

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

**Subjects** — `find` (Mono = LIMIT 1, Flux = all), `findFirst`/`findTop`/`findOne` (always Mono with LIMIT 1), `count` (`Mono<Long>`), `exists` (`Mono<Boolean>`), `delete`/`remove` (`Mono<Long>`).

**Keywords** — default (equality), `Not`, `LessThan`/`Lt` (alias `Before`), `LessThanEqual`/`Lte`, `GreaterThan`/`Gt` (alias `After`), `GreaterThanEqual`/`Gte`, `Like`, `StartingWith`/`StartsWith`, `EndingWith`/`EndsWith`, `Containing`/`Contains`, `In`, `NotIn`, `Between` (consumes two parameters), `IsNull` / `Null`, `IsNotNull` / `NotNull`, `True` / `IsTrue`, `False` / `IsFalse`.

**Connectors** — `And` / `Or` (left-to-right; no precedence — parenthesisation matches Spring Data conventions).

**Sort** — `OrderBy<Property>(Asc|Desc)?(And<Property>(Asc|Desc)?)*` appended after the predicate clause.

**Property resolution** — greedy match against the entity's reflective top-level fields. Longer property names win to avoid prefix ambiguity. Method names use lowerCamelCase form (`findByEmailAddress` → property `emailAddress`).

**Limitations** — `@Embedded` paths in method names, `IgnoreCase`, dynamic `Pageable` parameters, projections, and `@Query`-style native queries are not supported in derived names. Use `findAll(QuerySpec)` (or, with [`nova-metamodel`](metamodel.md), the generated property-name constants) for those cases.

Misuse — unknown property, parameter-count mismatch, unrecognized keyword suffix — fails at the first call to that method with an `IllegalArgumentException` carrying a precise diagnostic. Method names whose subject prefix does not match (`saveAndPublish`, `magicMethod`, …) fall through to the existing `UnsupportedOperationException` as before.
