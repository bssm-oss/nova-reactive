<!-- SPDX-License-Identifier: Apache-2.0 -->

# Spring Boot & Spring Data

## Spring Boot starter

Adding `nova-spring-boot-starter` registers every core bean via `NovaAutoConfiguration`. User-defined beans are guarded with `@ConditionalOnMissingBean` and are never overridden.

```kotlin
// build.gradle.kts
dependencies {
    implementation("io.github.bssm-oss:nova-spring-boot-starter:1.0.1")
    implementation("io.github.bssm-oss:nova-dialect-postgresql:1.0.1")
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

Add a `SqlExecutionListener` bean (e.g. `MicrometerSqlExecutionListener`) to the context and it is automatically composed into the executor.

### Auto-configuration properties

| Property                          | Type       | Default                       | Description                                          |
|-----------------------------------|------------|-------------------------------|------------------------------------------------------|
| `nova.pool.initial-size`          | `Integer`  | `PoolConfig.defaults()` value | Initial connection count                              |
| `nova.pool.max-size`              | `Integer`  | `PoolConfig.defaults()` value | Maximum connection count                              |
| `nova.pool.max-idle-time`         | `Duration` | `PoolConfig.defaults()` value | Idle-connection expiration                            |
| `nova.pool.acquire-timeout`       | `Duration` | `PoolConfig.defaults()` value | Acquire wait timeout                                  |
| `nova.slow-query.threshold-ms`    | `Long`     | (unset)                       | When set, registers `SlowQueryLoggingListener`         |

> The starter only exposes a `PoolConfig` bean; it does not bundle a pool implementation such as `r2dbc-pool`. If you need pooling, add the dependency yourself and feed this `PoolConfig` into your `ConnectionFactory` bean.

---

## Spring Data-style repositories (`nova-spring-data`)

The familiar `interface ... extends ReactiveCrudRepository<T, ID>` pattern is available as a separate dependency (`io.github.bssm-oss:nova-spring-data:1.0.1`). It depends only on Spring Framework's `spring-context` — not on Spring Data Commons.

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

Derived-query parsing (`findByEmail`) and similar magic are not supported — use an explicit `findAll(QuerySpec)` or a native query. This is consistent with Nova's project focus of avoiding magic.
