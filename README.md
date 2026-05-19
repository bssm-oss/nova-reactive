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

**Nova**는 R2DBC와 Project Reactor 위에서 동작하는 가벼운 **리액티브 ORM**입니다.
JPA에 친숙한 어노테이션 모델을 유지하면서, 모든 영속성 API를 `Mono`/`Flux`로 노출해
넌블로킹 데이터 파이프라인에 자연스럽게 녹아듭니다.

엔티티 매핑, 쿼리 DSL, 트랜잭션 경계, SQL 렌더링, 스키마 생성을 한 곳에서 다루지만
다이얼렉트는 분리된 모듈로 떨어져 있어 필요한 데이터베이스만 골라 의존할 수 있습니다.

---

## Table of contents

- [Project Focus](#project-focus)
- [Principles](#principles)
- [Requirements](#requirements)
- [Modules](#modules)
- [Getting started](#getting-started)
- [Defining entities](#defining-entities)
- [Composite types](#composite-types)
- [Indexes and unique constraints](#indexes-and-unique-constraints)
- [CRUD operations](#crud-operations)
- [Query DSL](#query-dsl)
- [Transactions](#transactions)
- [Pessimistic locking](#pessimistic-locking)
- [Retry policy](#retry-policy)
- [SQL execution hook](#sql-execution-hook)
- [Metrics (Micrometer)](#metrics-micrometer)
- [Connection pool (nova-r2dbc)](#connection-pool-nova-r2dbc)
- [Dialects](#dialects)
- [Schema generation](#schema-generation)
- [Schema migration](#schema-migration)
- [Spring Boot starter](#spring-boot-starter)
- [Building from source](#building-from-source)
- [Project layout](#project-layout)
- [Roadmap](#roadmap)
- [Contributing](#contributing)
- [License](#license)

---

## Project Focus

Nova는 "**작지만 충분한 리액티브 ORM**"을 지향합니다.

- 풀스택 ORM(예: Hibernate)이 가져오는 영속성 컨텍스트, 1차 캐시, 지연 로딩의 복잡도를 피하고
- Spring Data R2DBC보다는 더 깊은 매핑/쿼리 DSL을 제공하되,
- 코어 모듈은 **R2DBC SPI에만** 의존하도록 유지합니다.

> **Nova is not** a streaming framework, a query builder for arbitrary SQL, or a drop-in
> replacement for JPA. 작은 표면적의 리액티브 데이터 액세스 계층이 목표입니다.

## Principles

- **Reactive-first**: 모든 영속성 API는 `Mono`/`Flux`를 반환하며, 블로킹 호출이 노출되지 않습니다.
- **Dialect-pluggable**: SQL 렌더링과 스키마 생성을 `Dialect` 인터페이스 뒤로 분리해 DB별 모듈을 자유롭게 추가할 수 있습니다.
- **JPA-friendly**: `@Entity`, `@Table`, `@Id`, `@Column`, `@GeneratedValue` 등 익숙한 어노테이션으로 진입 장벽을 낮춥니다.
- **Explicit**: 마법 같은 자동 동작을 줄이고, 트랜잭션 경계와 식별자 상태를 호출자가 명시적으로 통제합니다.

---

## Requirements

Nova는 다음 환경에서 테스트되었습니다.

|                  | Main version (dev)              | Notes                                  |
|------------------|---------------------------------|----------------------------------------|
| Java             | 21                              | Gradle Toolchains로 자동 해석          |
| Build            | Gradle 8.10+                    | Wrapper(`./gradlew`) 동봉              |
| Reactive Runtime | Project Reactor 3.7.x           |                                        |
| Driver SPI       | R2DBC SPI 1.0.0.RELEASE         |                                        |
| PostgreSQL       | 14, 15, 16, 17                  | `nova-dialect-postgresql`              |
| MySQL            | 8.0, 8.4                        | `nova-dialect-mysql`                   |

> **Note**: Nova 코어는 R2DBC **SPI**에만 의존합니다. 실제 사용 시에는 데이터베이스에 맞는
> R2DBC 드라이버(`r2dbc-postgresql`, `r2dbc-mysql` 등)를 별도로 의존성에 추가하세요.

---

## Modules

| Module                       | Description                                                                  |
|------------------------------|------------------------------------------------------------------------------|
| `nova-core`                  | 어노테이션, 메타데이터, 쿼리 DSL, SQL 렌더링, 트랜잭션, `ReactiveEntityOperations`, `ReactiveRetryTemplate` 등 핵심 추상화 |
| `nova-r2dbc`                 | R2DBC SPI 어댑터 — `R2dbcSqlExecutor`, `R2dbcTransactionManager`, `SqlExecutionListener` hook |
| `nova-dialect-postgresql`    | PostgreSQL 다이얼렉트 (`$N` bind marker, `bigserial`/`serial` identity, `RETURNING`) |
| `nova-dialect-mysql`         | MySQL 다이얼렉트 (`?` bind marker, `auto_increment` identity)                  |
| `nova-dialect-h2`            | H2 다이얼렉트 (`?` bind marker, double-quote 식별자, `GENERATED ALWAYS AS IDENTITY`, `RETURNING`) |
| `nova-spring-boot-starter`   | Spring Boot 자동 구성 — `NovaAutoConfiguration` + `nova.*` 프로퍼티 바인딩      |
| `nova-metrics-micrometer`    | Micrometer 어댑터 — `MicrometerSqlExecutionListener` (timer + error counter) |

각 다이얼렉트 모듈은 `Dialect` 인터페이스만 구현하며, 코어 모듈에 새로운 의존성을 추가하지 않습니다.
Spring Boot starter와 Micrometer 어댑터는 선택 모듈로, 사용하지 않으면 의존성에 추가할 필요가 없습니다.

---

## Getting started

### 1. 의존성 추가

```kotlin
// build.gradle.kts
dependencies {
    implementation("io.nova:nova-core:1.0-SNAPSHOT")

    // 사용할 데이터베이스 다이얼렉트만 추가합니다
    implementation("io.nova:nova-dialect-postgresql:1.0-SNAPSHOT")
}
```

Groovy DSL을 쓰는 경우:

```groovy
// build.gradle
dependencies {
    implementation 'io.nova:nova-core:1.0-SNAPSHOT'
    implementation 'io.nova:nova-dialect-postgresql:1.0-SNAPSHOT'
}
```

> **Note**: Nova는 아직 Maven Central에 GA로 배포되지 않았습니다 (1.0-SNAPSHOT). resolve가 실패한다면
> 저장소에서 직접 체크아웃 후 `./gradlew publishToMavenLocal`을 실행하고 `repositories { mavenLocal() }`를
> 추가하는 방식이 가장 확실합니다.

```kotlin
// build.gradle.kts
repositories {
    mavenLocal()       // ./gradlew publishToMavenLocal 로 설치한 SNAPSHOT 사용
    mavenCentral()
}
```

### 2. 엔티티 정의

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

### 3. 작업 실행

```java
Dialect dialect = new PostgresqlDialect();
ReactiveEntityOperations operations = /* SimpleReactiveEntityOperations 구성 */;

operations.save(new Account(null, "user@example.com", true))
          .flatMap(saved -> operations.findById(Account.class, saved.getId()))
          .subscribe(System.out::println);
```

---

## Defining entities

Nova의 어노테이션은 `io.nova.annotation` 패키지에 있으며, JPA와 의미를 최대한 일치시킵니다.

| Annotation        | Purpose                                                                                  |
|-------------------|------------------------------------------------------------------------------------------|
| `@Entity`         | 영속 가능 클래스 표시. `name` 미지정 시 클래스 이름 기반 기본 명명 규칙이 적용됩니다.            |
| `@Table`          | 테이블 이름 명시. 미지정 시 `NamingStrategy`가 결정합니다.                                    |
| `@Id`             | 식별자 필드 지정. 엔티티당 정확히 하나여야 합니다.                                              |
| `@GeneratedValue` | 식별자 생성 전략 (`IDENTITY`, `AUTO`, `SEQUENCE`, `UUID`, `NONE`). `SEQUENCE`는 `generator` 속성으로 시퀀스 이름 지정. |
| `@Column`         | 컬럼 이름, `nullable` 등 매핑 메타데이터.                                                     |
| `@CreatedAt`      | insert 시점에 현재 시각 자동 채움 (`Instant`/`LocalDateTime`/`OffsetDateTime`). 사용자가 값을 미리 set하면 보존. |
| `@UpdatedAt`      | insert / update / partial update / Updater 경로에서 항상 현재 시각으로 덮어쓰기.                |
| `@SoftDelete`     | delete 호출을 `UPDATE deleted_at = now`로 변환. 모든 SELECT 경로에 자동 `WHERE deleted_at IS NULL` 추가. |
| `@Version`        | optimistic locking. `Long`/`Integer`/`Short` 지원. 충돌 시 `OptimisticLockingFailureException`. |
| `@PrePersist`     | 엔티티 lifecycle 콜백 — insert 직전 호출 (`void` no-arg method).                              |
| `@PreUpdate`      | update / partial update 직전 호출.                                                            |
| `@PostLoad`       | findById / findAll hydration 직후 호출.                                                       |
| `@PreRemove`      | delete 직전 (soft/hard 무관) 호출.                                                            |
| `@Embeddable`     | 자체 식별자 없이 호스트 엔티티 테이블에 컬럼들로 펼쳐지는 composite value type 표시 (TYPE-level). |
| `@Embedded`       | 엔티티 필드가 `@Embeddable` 타입을 호스트 컬럼들로 펼쳐 매핑됨을 표시 (FIELD-level).             |
| `@Index`          | 테이블 레벨 secondary index (TYPE-level, `@Repeatable`). `name` 미지정 시 `ix_{table}_{cols}` 자동 생성. |
| `@UniqueConstraint` | 테이블 레벨 unique constraint (TYPE-level, `@Repeatable`). `name` 미지정 시 `uk_{table}_{cols}` 자동 생성. |

엔티티 메타데이터는 `EntityMetadataFactory`가 한 번만 분석해 캐시하며, 다음 조건을 강제합니다.

- `@Entity`가 반드시 있어야 합니다.
- `@Id` 필드는 **정확히 하나** 존재해야 합니다.
- 기본 생성자가 필요합니다.
- 지원하지 않는 타입은 명시적으로 거부되며 `AttributeConverter`로 확장할 수 있습니다.
- `@CreatedAt`/`@UpdatedAt`/`@SoftDelete`/`@Version`이 중복 선언되거나 지원 외 타입에 붙으면 metadata 생성 시점에 fail-fast.
- `property name → PersistentProperty` 인덱스를 한 번 빌드해 모든 lookup이 O(1).

---

## Composite types

여러 컬럼을 묶어 도메인 의미를 가진 단일 객체로 매핑하고 싶을 때 `@Embeddable` value type을 정의하고 호스트 엔티티에서 `@Embedded` 필드로 사용합니다. 컬럼은 호스트 테이블에 그대로 펼쳐지므로 별도 join이 발생하지 않습니다.

```java
@Embeddable
public static class Address {
    private String city;
    private String street;
    private String zip;

    public Address() {}

    public Address(String city, String street, String zip) {
        this.city = city;
        this.street = street;
        this.zip = zip;
    }
    // getters...
}

@Entity
@Table("customer")
public static class Customer {
    @Id
    private Long id;

    private String name;

    @Embedded
    private Address shipping;

    public Customer() {}
}
```

컬럼 이름은 `{field name (snake_case)}_{sub property column name}` 규칙으로 합성됩니다 — 위 예시는 `shipping_city`, `shipping_street`, `shipping_zip` 컬럼으로 펼쳐집니다.

`@Embeddable` 타입은 자체 식별자를 가질 수 없으며 (`@Id` 금지), 내부 sub-property에 `@Version`/`@SoftDelete` 같은 marker를 두는 것도 metadata 생성 시점에 fail-fast로 거부됩니다.

---

## Indexes and unique constraints

테이블 레벨 secondary index와 unique constraint는 엔티티 타입에 `@Index` / `@UniqueConstraint`로 선언합니다. 두 어노테이션 모두 `@Repeatable`이므로 같은 엔티티에 여러 개를 자유롭게 붙일 수 있습니다.

```java
@Entity
@Table("accounts")
@Index(columns = {"email"})                                 // ix_accounts_email 로 자동 명명
@Index(name = "ix_active_created", columns = {"active", "created_at"})
@UniqueConstraint(columns = {"tenant_id", "email"})         // uk_accounts_tenant_id_email
public class Account {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column("tenant_id")
    private Long tenantId;

    @Column("email")
    private String email;

    @Column("active")
    private boolean active;

    @CreatedAt
    @Column("created_at")
    private Instant createdAt;
}
```

- `name`이 빈 문자열이면 `ix_{table}_{col1}_{col2}_...` / `uk_{table}_{col1}_{col2}_...` 패턴으로 자동 생성됩니다.
- 자동 생성된 이름이 dialect 식별자 길이 한도를 넘기면 짧은 해시 접미사로 축약(truncate)됩니다.
- `columns()`는 1개 이상이어야 하며 실제 컬럼 이름(`@Column`이 명시한 값 또는 snake_case 변환 결과)을 사용해야 합니다. 메타데이터에 존재하지 않는 컬럼은 fail-fast로 거부됩니다.

실제 DDL은 [Schema generation](#schema-generation)의 `createIndexes(...)`로 발행합니다.

---

## CRUD operations

`ReactiveEntityOperations`는 Nova의 메인 진입점입니다. 단건 CRUD 외에 batch / partial update / projection / native SQL / aggregation / compiled query 진입점을 제공합니다.

```java
public interface ReactiveEntityOperations {
    // 단건
    <T>      Mono<T>       save(T entity);
    <T>      Mono<T>       update(T entity, Iterable<String> fields);          // partial update
    <T, ID>  Mono<T>       findById(Class<T> entityType, ID id);
    <T>      Mono<Long>    delete(T entity);
    <T, ID>  Mono<Long>    deleteById(Class<T> entityType, ID id);

    // 다건 / 배치
    <T>      Flux<T>       saveAll(Iterable<T> entities);                       // generated id batch 회수 포함
    <T, ID>  Flux<T>       findAllById(Class<T> entityType, Iterable<ID> ids);   // 단일 IN 쿼리
    <T>      Flux<T>       findAll(Class<T> entityType, QuerySpec querySpec);
    <T>      Mono<Long>    deleteAll(Iterable<T> entities);
    <T, ID>  Mono<Long>    deleteAllById(Class<T> entityType, Iterable<ID> ids);
    <T>      Mono<Long>    deleteAll(Class<T> entityType, QuerySpec querySpec);  // criteria 기반 bulk delete

    // 집계 / 카운트
    <T>      Mono<Long>    count(Class<T> entityType, QuerySpec querySpec);
    <T>      Mono<Boolean> exists(Class<T> entityType, QuerySpec querySpec);

    // Updater DSL — entity 인스턴스 없이 criteria 기반 partial UPDATE
    <T>      Mono<Long>    update(Class<T> entityType, Updater<T> updater);

    // Projection — entity 일부 컬럼을 record/DTO에 매핑
    <E, P>   Flux<P>       findAll(Projection<E, P> projection, QuerySpec querySpec);

    // Native SQL
              Mono<Long>   executeNative(NativeQuery query);
    <T>      Flux<T>       queryNative(NativeQuery query, Function<RowAccessor, T> mapper);
    <T>      Mono<T>       queryNativeOne(NativeQuery query, Function<RowAccessor, T> mapper);

    // Aggregations DSL
    <T>      Flux<AggregateRow> aggregate(Class<T> entityType, AggregateSpec spec);
    <T, R>   Flux<R>       aggregate(Class<T> entityType, AggregateSpec spec, Function<RowAccessor, R> mapper);

    // CompiledQuery — SQL 한 번 렌더 후 binding만 교체 재실행
    <T>      Flux<T>       findAll(Class<T> entityType, CompiledQuery query, Object... bindings);
              Mono<Long>   execute(CompiledQuery query, Object... bindings);

    // Transaction
    <R>      Mono<R>       inTransaction(Function<ReactiveEntityOperations, Mono<R>> callback);
}
```

`save`는 `EntityStateDetector`가 식별자 상태를 보고 **insert / update**를 자동으로 선택합니다.
`@Version` 엔티티의 update/delete는 affected rows == 0일 때 `OptimisticLockingFailureException`을 발행하며, `@SoftDelete` 엔티티의 delete는 자동으로 UPDATE로 변환됩니다. `@PrePersist`/`@PreUpdate`/`@PostLoad`/`@PreRemove` 콜백은 audit 적용 직후에 호출되어 사용자가 audit 기본값을 overrides할 수 있습니다.

---

## Query DSL

문자열 SQL을 직접 작성하지 않고, 타입 안전한 술어(predicate)와 정렬/페이징을 조합합니다.

```java
import static io.nova.query.Criteria.*;

QuerySpec spec = QuerySpec.empty()
    .where(and(
        eq("active", true),
        or(like("email", "%@example.com"), isNull("email"))
    ))
    .orderBy(Sort.by("id").descending())
    .page(Pageable.of(0, 20));

Flux<Account> accounts = operations.findAll(Account.class, spec);
Mono<Long>    total    = operations.count(Account.class, spec);
```

지원 연산자: `eq`, `ne`, `gt`, `gte`, `lt`, `lte`, `like`, `in`, `notIn`, `between`,
`isNull`, `isNotNull`, 그리고 이들을 묶는 `and` / `or` / `not`.

빈 컬렉션 처리: `in([])` → `1=0` (항상 false), `notIn([])` → `1=1` (항상 true). Hibernate 6.3+/jOOQ 컨벤션을 따라 NPE 대신 의미가 명확한 SQL을 발행합니다.

### Updater DSL — entity 없이 partial UPDATE

```java
operations.update(Account.class, Updater.of(Account.class)
        .set("email", "x@nova.io")
        .set("active", true)
        .where(Criteria.where("id").gte(10L)));
```

`@UpdatedAt`이 metadata에 있으면 자동으로 SET 절에 포함됩니다.

### Projection — record / DTO 매핑

```java
record AccountEmail(Long id, String email) {}

Projection<Account, AccountEmail> projection = Projection.of(
        Account.class, AccountEmail.class, List.of("id", "email"));

Flux<AccountEmail> rows = operations.findAll(projection,
        QuerySpec.empty().where(Criteria.eq("active", true)));
```

record canonical constructor 또는 단일 명시적 constructor를 사용. soft delete alive guard 자동 적용.

### Aggregations DSL

```java
AggregateSpec spec = AggregateSpec.of(
        Aggregation.countDistinct("email").as("unique_emails"),
        Aggregation.sum("balance").as("total"))
    .groupBy("active")
    .having(Criteria.where("total").gt(0L));

Flux<AggregateRow> rows = operations.aggregate(Account.class, spec);
```

지원: `count`, `countDistinct`, `sum`, `avg`, `min`, `max`. `groupBy`, `having`, `orderBy` 조합. `AggregateRow.get(column, type)`은 driver raw type을 그대로 노출.

### Cursor (keyset) pagination

```java
Cursor cursor = Cursor.of(CursorField.asc("id", lastId));
QuerySpec spec = QuerySpec.empty()
        .where(Criteria.eq("active", true))
        .orderBy(Sort.by(Sort.Order.asc("id")))
        .cursor(cursor, 20);
```

cursor 설정 시 OFFSET 무시, lexicographic keyset 조건이 WHERE에 자동 추가됩니다.

### NativeQuery — raw SQL

```java
NativeQuery query = NativeQuery.of("select count(*) from accounts where active = ?")
        .bind(true);

Mono<Long> total = operations.queryNativeOne(query,
        row -> row.get("count", Long.class));
```

### CompiledQuery — SQL 한 번 렌더, binding만 교체

```java
CompiledQuery compiled = dialect.sqlRenderer().compileSelect(metadata,
        QuerySpec.empty().where(Criteria.eq("email", "placeholder")));

// 동일 SQL을 다른 binding으로 반복 실행 — renderer 호출 없음
Flux<Account> a = operations.findAll(Account.class, compiled, "a@nova.io");
Flux<Account> b = operations.findAll(Account.class, compiled, "b@nova.io");
```

---

## Transactions

`ReactiveTransactionOperations`는 콜백을 트랜잭션 경계 안에서 실행합니다. 기본 호출 외에 `Propagation` + `IsolationLevel` + `readOnly` 힌트를 담은 `TransactionDefinition` overload를 제공합니다.

```java
// 기본 — Propagation.REQUIRED, IsolationLevel.DEFAULT, readOnly=false
operations.inTransaction(tx ->
    tx.save(new Account(null, "a@example.com", true))
      .then(tx.save(new Account(null, "b@example.com", true)))
).subscribe();

// REQUIRES_NEW — 부모 tx를 suspend하고 새 connection으로 격리된 tx 실행
operations.inTransaction(TransactionDefinition.requiresNew(), tx -> ...);

// readOnly + SERIALIZABLE
TransactionDefinition def = TransactionDefinition.DEFAULT
        .with(IsolationLevel.SERIALIZABLE)
        .withReadOnly(true);
operations.inTransaction(def, tx -> ...);
```

지원 `Propagation`: `REQUIRED`, `REQUIRES_NEW`, `NESTED` (SAVEPOINT), `MANDATORY`, `SUPPORTS`, `NOT_SUPPORTED`, `NEVER` — Spring 의미 그대로.

- 콜백 내부에서 예외/`Mono.error`가 발생하면 **자동 롤백**됩니다. `NESTED`는 SAVEPOINT까지만 롤백.
- 트랜잭션 컨텍스트는 Reactor `Context`를 통해 전달되므로 스레드 누수에 안전 (`ThreadLocal` 사용 안 함).

---

## Pessimistic locking

`QuerySpec.forUpdate()` / `forShare()`로 SELECT 결과 행에 대한 pessimistic lock 강도를 지정할 수 있습니다. 락 절은 반드시 **트랜잭션 안**에서만 의미를 가지므로 `inTransaction(...)` 콜백 내부에서 사용하세요.

```java
import io.nova.query.LockMode;

operations.inTransaction(tx ->
    tx.findAll(Account.class,
            QuerySpec.empty()
                .where(Criteria.eq("id", 42L))
                .forUpdate())                      // SELECT ... FOR UPDATE
      .next()
      .flatMap(account -> {
          account.setEmail("locked-" + account.getEmail());
          return tx.save(account);
      })
).subscribe();
```

지원하는 모드:

| `LockMode`   | SQL 절              | 의미 (표준 SQL)                          |
|--------------|---------------------|-----------------------------------------|
| `NONE`       | (none, 기본값)      | 락 절 생성 안 함                          |
| `FOR_UPDATE` | `FOR UPDATE`        | 다른 트랜잭션의 동시 수정/공유 락 차단     |
| `FOR_SHARE`  | `FOR SHARE`         | 배타 락은 차단하되 공유 읽기는 허용         |

`Dialect.lockClause(LockMode)`의 기본 구현은 표준 SQL `FOR UPDATE` / `FOR SHARE`를 사용하며 PostgreSQL, MySQL 8.0+, H2 모두 그대로 동작합니다. dialect별 변형이 필요하면 이 메서드를 override 합니다.

> **주의**: 실제 잠금 의미는 트랜잭션 격리 수준에 따라 달라집니다. `READ COMMITTED`에서는 select된 행만, `REPEATABLE READ` 이상에서는 phantom/gap 동작이 달라질 수 있으니 dialect 매뉴얼을 함께 참고하세요.

---

## Retry policy

낙관적 락 충돌처럼 일시적(transient)인 예외에 대해 exponential backoff 재시도를 적용하는 reactive helper `ReactiveRetryTemplate`을 제공합니다. Reactor의 `Retry.backoff(...)`을 thin wrapping한 immutable 구조입니다.

```java
import io.nova.retry.ReactiveRetryTemplate;

ReactiveRetryTemplate retry = ReactiveRetryTemplate.optimisticLockRetry();
// 정책: maxAttempts=3, initialBackoff=10ms, multiplier=2.0, maxBackoff=200ms,
//      retryable = OptimisticLockingFailureException::isInstance

retry.execute(
    operations.inTransaction(tx ->
        tx.findById(Account.class, 42L)
          .flatMap(account -> {
              account.setEmail("retried@nova.io");
              return tx.save(account);    // @Version 충돌 시 OptimisticLockingFailureException
          })
    )
).subscribe();
```

사용자 정의 정책은 builder로 조립합니다.

```java
ReactiveRetryTemplate custom = ReactiveRetryTemplate.builder()
        .maxAttempts(5)
        .initialBackoff(Duration.ofMillis(20))
        .backoffMultiplier(2.0)
        .maxBackoff(Duration.ofSeconds(1))
        .retryable(OptimisticLockingFailureException.class::isInstance)
        .build();
```

운영 시 유의할 점:

- **Jitter**: Reactor `Retry.backoff`는 기본 ±50% jitter를 적용합니다. 실측 대기는 `[T*0.5, T*1.5]` 범위에서 무작위 결정되므로 `maxBackoff`를 일시적으로 초과할 수 있습니다.
- **단일 시도 short-circuit**: `maxAttempts == 1`이면 backoff 설정은 모두 무시되고 wrapper를 거치지 않은 것과 동일하게 한 번만 실행합니다.
- **Flux 재구독**: `execute(Flux<T>)`는 `retryWhen`을 사용하므로 source가 일부 값을 emit한 뒤 retryable 예외로 종료되면 **이미 발행된 값이 재시도 후 다시 emit**됩니다. 멱등하지 않은 downstream에는 별도 dedup/idempotency key가 필요합니다.

---

## SQL execution hook

모든 SQL 실행을 감시할 수 있는 `SqlExecutionListener` 인터페이스를 제공합니다. R2DBC adapter가 execute/query/batch 경로의 before/after/error를 발화합니다.

```java
SqlExecutionListener slowLog = new SlowQueryLoggingListener(Duration.ofMillis(100));
SqlExecutionListener composite = CompositeSqlExecutionListener.of(slowLog, otherListener);

R2dbcSqlExecutor executor = new R2dbcSqlExecutor(connectionFactory, dialect, composite);
```

`SlowQueryLoggingListener`는 threshold 이상 쿼리만 로깅하며 bindings는 PII 보호로 노출하지 않습니다. `onError`는 threshold와 무관하게 항상 로깅.

---

## Metrics (Micrometer)

`nova-metrics-micrometer` 모듈은 `SqlExecutionListener`를 구현한 `MicrometerSqlExecutionListener`를 제공합니다. 모든 execute / query / batch 경로에 대해 timer를 기록하고 실패 시 error counter를 함께 증가시킵니다.

```kotlin
// build.gradle.kts
implementation("io.nova:nova-metrics-micrometer:1.0-SNAPSHOT")
implementation("io.micrometer:micrometer-core:1.14.0")
```

```java
import io.micrometer.core.instrument.MeterRegistry;
import io.nova.metrics.micrometer.MicrometerSqlExecutionListener;

MeterRegistry registry = /* PrometheusMeterRegistry, SimpleMeterRegistry 등 */;

// 기본 prefix "nova.sql" 사용
SqlExecutionListener listener = new MicrometerSqlExecutionListener(registry);

// 또는 커스텀 prefix
SqlExecutionListener listener = new MicrometerSqlExecutionListener(registry, "app.db");

R2dbcSqlExecutor executor = new R2dbcSqlExecutor(connectionFactory, dialect, listener);
```

발행되는 meter:

| Meter                    | Type     | Tags                                              | 의미                              |
|--------------------------|----------|---------------------------------------------------|-----------------------------------|
| `<prefix>.query`         | Timer    | `outcome=success`                                  | 성공한 SQL 실행 elapsed time      |
| `<prefix>.query`         | Timer    | `outcome=error`, `exception=<SimpleName>`         | 실패한 SQL 실행 elapsed time      |
| `<prefix>.errors`        | Counter  | `outcome=error`, `exception=<SimpleName>`         | 실패 횟수                          |

> **Cardinality 주의**: 익명 lambda/anonymous class 예외의 경우 `getSimpleName()`이 빈 문자열이 되어 `exception=Anonymous`로 fallback됩니다. 사용자 정의 예외와 driver-wrapped 예외가 무한히 누적되는 시나리오에서는 Prometheus 등의 시계열 cardinality가 폭주할 수 있으므로 upstream에서 예외 wrapping을 제한하는 정책을 권장합니다.

다른 listener와 함께 쓰려면 `CompositeSqlExecutionListener.of(...)`로 합치세요.

---

## Connection pool (nova-r2dbc)

R2DBC pool 외에 reachability probe만 노출하는 가벼운 헬퍼를 제공합니다.

```java
PoolConfig config = PoolConfig.of(2, 10);   // initialSize, maxSize
PoolHealthProbe probe = new SimplePoolHealthProbe(connectionFactory);
Mono<PoolHealth> health = probe.probe();    // reachable / 카운터 정보
```

`r2dbc-pool` 의존성을 추가하지 않고 `ConnectionFactory`만 사용해 acquire/release smoke test를 수행합니다 (counters는 0, `reachable`만 의미).

---

## Dialects

`Dialect`는 데이터베이스별 차이를 캡슐화하는 단일 인터페이스입니다.

```java
public interface Dialect {
    String              name();
    String              quote(String identifier);
    BindMarkerStrategy  bindMarkers();
    SqlRenderer         sqlRenderer();
    SchemaGenerator     schemaGenerator();
}
```

| Dialect              | Bind marker | Identity column                                                 | Quote      | Generated keys           | SEQUENCE                |
|----------------------|-------------|-----------------------------------------------------------------|------------|--------------------------|--------------------------|
| `PostgresqlDialect`  | `$1`, `$2`  | `bigserial` / `serial` primary key                              | `" "`      | `RETURNING` 절            | `nextval('seq')`         |
| `MySqlDialect`       | `?`         | `bigint primary key auto_increment`                             | `` ` ` ``  | `Statement.returnGeneratedValues` | 미지원 (UOE)     |
| `H2Dialect`          | `?`         | `bigint generated always as identity primary key`               | `" "`      | `Statement.returnGeneratedValues` (driver-side) | 미지원 (UOE)             |

`@GeneratedValue(strategy = SEQUENCE, generator = "account_seq")`이면 dialect의 `sequenceNextValueSql(generator)`로 `Dialect.SEQUENCE_VALUE_COLUMN` alias를 가진 SELECT을 발행해 id를 미리 받아옵니다. `UUID`는 `java.util.UUID` 또는 `String` field에 대해 ops가 INSERT 직전 `UUID.randomUUID()`를 stamp합니다.

새 다이얼렉트는 `AbstractSqlRenderer`와 `AbstractSchemaGenerator`를 확장해 핵심 차이만 오버라이드하면 됩니다.

---

## Schema generation

`SchemaGenerator`는 엔티티 메타데이터로부터 `CREATE TABLE` 문을 생성합니다.

```java
Dialect dialect = new MySqlDialect();
EntityMetadata<Account> metadata = metadataFactory.getEntityMetadata(Account.class);

String ddl = dialect.schemaGenerator().createTable(metadata);
// → CREATE TABLE `accounts` (`id` bigint primary key auto_increment, ...)
```

개발 환경의 초기화 스크립트나 통합 테스트의 픽스처 준비에 그대로 사용할 수 있습니다.

---

## Schema migration

`SchemaGenerator`는 `createTable` 외에 가벼운 마이그레이션용 DDL 헬퍼를 함께 제공합니다. 모두 dialect 모듈(`AbstractSchemaGenerator`)이 구현하며, 미지원 dialect는 `UnsupportedOperationException`을 던집니다.

```java
SchemaGenerator schema = dialect.schemaGenerator();
EntityMetadata<Account> metadata = metadataFactory.getEntityMetadata(Account.class);

// 1) 테이블 + 모든 @Index / @UniqueConstraint를 한 번에 발행
String createTable = schema.createTable(metadata);
List<String> indexDdls = schema.createIndexes(metadata);
// indexDdls 예: ["create index \"ix_accounts_email\" on \"accounts\" (\"email\")",
//               "create unique index \"uk_accounts_tenant_id_email\" on \"accounts\" (\"tenant_id\", \"email\")"]

// 2) 컬럼 추가 — 기존 metadata의 nullable / identity / 기본 column type 규칙을 그대로 사용
PersistentProperty newColumn = metadata.findProperty("nickname").orElseThrow();
String addSql = schema.alterTableAddColumn(metadata, newColumn);
// → alter table "accounts" add column "nickname" varchar(255)

// 3) 컬럼 제거 — 컬럼이 metadata에 존재하지 않으면 IllegalArgumentException으로 fail-fast
String dropSql = schema.alterTableDropColumn(metadata, "legacy_flag");
// → alter table "accounts" drop column "legacy_flag"
```

발행된 DDL은 `executeNative(NativeQuery.of(ddl))`로 R2DBC adapter를 통해 실행하거나, 별도 migration tool(Flyway 등)에 입력으로 넘길 수 있습니다.

- `createIndexes`의 기본 구현은 빈 리스트를 반환하지만 `AbstractSchemaGenerator`(모든 번들 dialect의 기반 클래스)에서 실제 DDL을 생성합니다.
- `alterTableDropColumn`은 컬럼 이름을 metadata와 cross-check 하므로 오타로 인한 잘못된 DROP을 사전에 막아줍니다.

---

## Spring Boot starter

`nova-spring-boot-starter`를 의존성에 추가하면 `NovaAutoConfiguration`이 모든 핵심 빈을 등록합니다. 사용자가 직접 정의한 빈은 `@ConditionalOnMissingBean`으로 보호되어 절대 덮어쓰지 않습니다.

```kotlin
// build.gradle.kts
dependencies {
    implementation("io.nova:nova-spring-boot-starter:1.0-SNAPSHOT")
    implementation("io.nova:nova-dialect-postgresql:1.0-SNAPSHOT")
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

지원 properties:

| Property                          | Type       | Default                       | 설명                                                |
|-----------------------------------|------------|-------------------------------|-----------------------------------------------------|
| `nova.pool.initial-size`          | `Integer`  | `PoolConfig.defaults()` 값    | 초기 connection 수                                   |
| `nova.pool.max-size`              | `Integer`  | `PoolConfig.defaults()` 값    | 최대 connection 수                                   |
| `nova.pool.max-idle-time`         | `Duration` | `PoolConfig.defaults()` 값    | 유휴 connection 만료 시간                            |
| `nova.pool.acquire-timeout`       | `Duration` | `PoolConfig.defaults()` 값    | acquire 대기 timeout                                 |
| `nova.slow-query.threshold-ms`    | `Long`     | (unset)                       | 설정 시 `SlowQueryLoggingListener` 자동 등록         |

> Starter는 `PoolConfig` 빈만 노출하고 `r2dbc-pool` 같은 pool 구현체를 번들하지 않습니다. pool이 필요하면 직접 의존성을 추가한 뒤 `ConnectionFactory` 빈을 만들 때 이 `PoolConfig`를 입력으로 사용하세요.

---

## Building from source

```bash
# 전체 빌드 + 테스트
./gradlew build

# 테스트만 실행
./gradlew test

# 코어만 빌드 (의존 모듈 포함)
./gradlew :nova-project:nova-core:build

# 특정 다이얼렉트만 테스트
./gradlew :nova-project:nova-dialects:nova-dialect-postgresql:test

# 깨끗하게 다시 빌드
./gradlew clean build
```

> Gradle Wrapper(`./gradlew`)가 동봉되어 있어 별도 Gradle 설치가 필요 없습니다.
> 첫 실행 시 Wrapper가 지정된 Gradle 배포본(현재 8.10.2)을 `~/.gradle/wrapper/`에 자동으로 받습니다.

---

## Project layout

```
nova/
├── settings.gradle.kts                                 # 모듈 선언, dependencyResolutionManagement
├── build.gradle.kts                                    # subprojects 공통 설정 (Java 21 Toolchain, JUnit5)
├── gradle/wrapper/                                     # Gradle Wrapper (8.10.2)
├── gradlew, gradlew.bat                                # Wrapper 실행 스크립트
└── nova-project/                                       # spring-boot 스타일 모듈 컨테이너
    ├── nova/                                           # 엄브렐러 — 모든 모듈 재노출
    ├── nova-core/                                      # 코어 추상화
    │   └── src/main/java/io/nova/
    │       ├── annotation/                             # @Entity, @Id, @Column, @GeneratedValue, @CreatedAt, @UpdatedAt, @SoftDelete, @Version, @PrePersist/Update/Remove, @PostLoad, @Embeddable, @Embedded, @Index, @UniqueConstraint
    │       ├── core/                                   # ReactiveEntityOperations, SqlExecutor, AuditApplier, EntityListenerInvoker, SqlExecutionListener, SlowQueryLoggingListener, CompositeSqlExecutionListener
    │       ├── convert/                                # AttributeConverter SPI
    │       ├── exception/                              # OptimisticLockingFailureException
    │       ├── metadata/                               # EntityMetadata, EntityMetadataFactory, PersistentProperty, NamingStrategy, IndexDefinition, UniqueConstraintDefinition
    │       ├── query/                                  # Criteria, Predicate, QuerySpec, Sort, Pageable, Updater, Projection, NativeQuery, Cursor, LockMode, AggregateSpec/Aggregation/AggregateRow
    │       ├── retry/                                  # ReactiveRetryTemplate (exponential backoff retry helper)
    │       ├── sql/                                    # Dialect, SqlRenderer, AbstractSqlRenderer, SchemaGenerator, AbstractSchemaGenerator, CompiledQuery
    │       └── tx/                                     # ReactiveTransactionOperations, TransactionContext, TransactionDefinition, Propagation, IsolationLevel
    ├── nova-r2dbc/                                     # R2DBC SPI 어댑터
    ├── nova-spring-boot-starter/                       # io.nova.boot — NovaAutoConfiguration, NovaProperties
    ├── nova-metrics-micrometer/                        # io.nova.metrics.micrometer — MicrometerSqlExecutionListener
    └── nova-dialects/
        ├── nova-dialect-postgresql/                    # PostgreSQL 다이얼렉트
        ├── nova-dialect-mysql/                         # MySQL 다이얼렉트
        └── nova-dialect-h2/                            # H2 다이얼렉트 (io.nova.dialect.h2)
```

> Maven 좌표는 평탄(`io.nova:nova-core`)하게 유지됩니다 — Gradle 경로만 `:nova-project:...`로 중첩.

---

## Roadmap

- [x] Optimistic locking (`@Version`) — Long/Integer/Short, conflict 시 `OptimisticLockingFailureException`
- [x] Soft delete (`@SoftDelete`) — delete를 UPDATE로 변환, SELECT에 alive guard 자동 적용
- [x] Audit fields (`@CreatedAt` / `@UpdatedAt`) — `Clock` 주입 가능
- [x] Entity lifecycle callbacks (`@PrePersist` / `@PreUpdate` / `@PostLoad` / `@PreRemove`)
- [x] Updater builder DSL — entity 없이 criteria 기반 partial UPDATE
- [x] Projection (record / DTO 매핑)
- [x] Aggregations (count distinct, sum, avg, min, max + groupBy + having)
- [x] Cursor / keyset pagination
- [x] NativeQuery — raw SQL 진입점
- [x] CompiledQuery — SQL 한 번 렌더 + binding 교체
- [x] SQL execution hook (`SqlExecutionListener`, `SlowQueryLoggingListener`)
- [x] Transaction propagation / isolation / readOnly hint
- [x] SEQUENCE / UUID id generation
- [x] Batch insert generated ID 회수
- [x] `@Embeddable` / `@Embedded` composite value type
- [x] `@Index` / `@UniqueConstraint` 테이블 레벨 어노테이션
- [x] Schema migration helpers (`createIndexes`, `alterTableAddColumn`, `alterTableDropColumn`)
- [x] H2 다이얼렉트 (`nova-dialect-h2`)
- [x] Spring Boot 자동 구성 (`nova-spring-boot-starter`)
- [x] Pessimistic locking (`QuerySpec.forUpdate()` / `forShare()` + `LockMode`)
- [x] Metrics 어댑터 (`nova-metrics-micrometer` — Micrometer)
- [x] Retry helper (`ReactiveRetryTemplate` — exponential backoff + jitter)
- [ ] 관계 매핑 (`@OneToMany`, `@ManyToOne`) — eager only, lazy proxy 없이
- [ ] JSON column type
- [ ] MariaDB / Oracle 다이얼렉트
- [ ] 1.0 GA 릴리스 및 Maven Central 배포

진행 중이거나 논의 중인 항목은 이슈 트래커를 참고하세요.

---

## Contributing

기여는 언제든 환영합니다.

1. 이슈를 먼저 열어 작업 범위와 방향을 합의합니다.
2. 브랜치를 만들고 변경합니다. PR 단위는 가능하면 **작게** 유지합니다.
3. `./gradlew build`로 모든 테스트가 통과하는지 확인합니다.
4. 커밋 메시지는 [Conventional Commits](https://www.conventionalcommits.org/) 규약을 권장합니다.

---

## License

Nova는 [Apache License 2.0](LICENSE) 하에 배포됩니다.

```
Copyright (c) Nova contributors
Licensed under the Apache License, Version 2.0
```
