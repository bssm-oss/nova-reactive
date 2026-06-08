<!-- SPDX-License-Identifier: Apache-2.0 -->

# Spring Boot & Spring Data

## Spring Boot starter

`nova-spring-boot-starter`를 의존성에 추가하면 `NovaAutoConfiguration`이 모든 핵심 빈을 등록합니다. 사용자가 직접 정의한 빈은 `@ConditionalOnMissingBean`으로 보호되어 절대 덮어쓰지 않습니다.

```kotlin
// build.gradle.kts
dependencies {
    implementation("io.github.bssm-oss:nova-spring-boot-starter:1.0.1")
    implementation("io.github.bssm-oss:nova-dialect-postgresql:1.0.1")
    runtimeOnly("org.postgresql:r2dbc-postgresql:1.0.7.RELEASE")
}
```

사용자 컨텍스트에 `ConnectionFactory`와 `Dialect` 빈이 모두 있을 때 활성화되며, 다음 빈을 자동으로 등록합니다 (모두 missing 시에만).

| Bean                          | Type                                | 비고                                                                  |
|-------------------------------|-------------------------------------|------------------------------------------------------------------------|
| `novaNamingStrategy`          | `DefaultNamingStrategy`             | 클래스 → snake_case 변환                                                |
| `novaEntityMetadataFactory`   | `EntityMetadataFactory`             | 메타데이터 캐싱                                                          |
| `novaEntityStateDetector`     | `EntityStateDetector`               | id 기반 insert/update 판정                                              |
| `novaTransactionManager`      | `R2dbcTransactionManager`           | Reactor Context 기반 tx 전파                                            |
| `novaSqlExecutor`             | `R2dbcSqlExecutor`                  | 컨텍스트의 모든 `SqlExecutionListener` 빈을 `CompositeSqlExecutionListener`로 자동 합성 |
| `novaEntityOperations`        | `SimpleReactiveEntityOperations`    | 사용자 진입점                                                            |
| `novaPoolConfig`              | `PoolConfig`                        | 항상 노출. 미지정 필드는 `PoolConfig.defaults()` 채택                    |
| `novaSlowQueryLoggingListener`| `SlowQueryLoggingListener`          | `nova.slow-query.threshold-ms`가 설정된 경우에만 등록                    |

`SqlExecutionListener` 빈(예: `MicrometerSqlExecutionListener`)을 컨텍스트에 추가하면 자동으로 executor에 합성됩니다.

### 자동 구성 properties

| Property                          | Type       | Default                       | 설명                                                |
|-----------------------------------|------------|-------------------------------|-----------------------------------------------------|
| `nova.pool.initial-size`          | `Integer`  | `PoolConfig.defaults()` 값    | 초기 connection 수                                   |
| `nova.pool.max-size`              | `Integer`  | `PoolConfig.defaults()` 값    | 최대 connection 수                                   |
| `nova.pool.max-idle-time`         | `Duration` | `PoolConfig.defaults()` 값    | 유휴 connection 만료 시간                            |
| `nova.pool.acquire-timeout`       | `Duration` | `PoolConfig.defaults()` 값    | acquire 대기 timeout                                 |
| `nova.slow-query.threshold-ms`    | `Long`     | (unset)                       | 설정 시 `SlowQueryLoggingListener` 자동 등록         |

> Starter는 `PoolConfig` 빈만 노출하고 `r2dbc-pool` 같은 pool 구현체를 번들하지 않습니다. pool이 필요하면 직접 의존성을 추가한 뒤 `ConnectionFactory` 빈을 만들 때 이 `PoolConfig`를 입력으로 사용하세요.

---

## Spring Data 스타일 Repository (`nova-spring-data`)

JPA/Spring Data 사용자에게 익숙한 `interface ... extends ReactiveCrudRepository<T, ID>` 패턴을 별도 의존(`io.github.bssm-oss:nova-spring-data:1.0.1`)으로 제공합니다. Spring Data Commons에는 의존하지 않으며 Spring Framework `spring-context`만 사용합니다.

```java
import io.nova.spring.data.ReactiveCrudRepository;

public interface AuthorRepository extends ReactiveCrudRepository<Author, Long> {
}

@Configuration
@EnableNovaRepositories(basePackages = "com.example.author")
class AppConfig {}
```

`@EnableNovaRepositories`가 base package를 스캔해 발견된 인터페이스마다 JDK proxy + `NovaRepositoryFactoryBean`을 등록합니다. 모든 메서드는 `ReactiveEntityOperations`(`novaEntityOperations` 빈)에 위임됩니다. 제공 메서드:

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

derived query parsing(`findByEmail`) 같은 magic은 지원하지 않습니다 — 명시적인 `findAll(QuerySpec)` 또는 native query를 사용하세요. Project Focus("magic 회피") 원칙과 일관됩니다.
