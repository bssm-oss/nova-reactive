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
- [CRUD operations](#crud-operations)
- [Query DSL](#query-dsl)
- [Transactions](#transactions)
- [Dialects](#dialects)
- [Schema generation](#schema-generation)
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
| `nova-core`                  | 어노테이션, 메타데이터, 쿼리 DSL, SQL 렌더링, 트랜잭션, `ReactiveEntityOperations` 등 핵심 추상화 |
| `nova-dialect-postgresql`    | PostgreSQL 다이얼렉트 (`$N` bind marker, `bigserial`/`serial` identity)        |
| `nova-dialect-mysql`         | MySQL 다이얼렉트 (`?` bind marker, `auto_increment` identity)                  |

각 다이얼렉트 모듈은 `Dialect` 인터페이스만 구현하며, 코어 모듈에 새로운 의존성을 추가하지 않습니다.

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
| `@GeneratedValue` | 식별자 생성 전략 (`IDENTITY` 등) 선택.                                                       |
| `@Column`         | 컬럼 이름, `nullable` 등 매핑 메타데이터.                                                     |

엔티티 메타데이터는 `EntityMetadataFactory`가 한 번만 분석해 캐시하며, 다음 조건을 강제합니다.

- `@Entity`가 반드시 있어야 합니다.
- `@Id` 필드는 **정확히 하나** 존재해야 합니다.
- 기본 생성자가 필요합니다.
- 지원하지 않는 타입은 명시적으로 거부되며 `AttributeConverter`로 확장할 수 있습니다.

---

## CRUD operations

`ReactiveEntityOperations`는 Nova의 메인 진입점입니다.

```java
public interface ReactiveEntityOperations {
    <T>      Mono<T>       save(T entity);
    <T, ID>  Mono<T>       findById(Class<T> entityType, ID id);
    <T>      Flux<T>       findAll(Class<T> entityType, QuerySpec querySpec);
    <T>      Mono<Long>    delete(T entity);
    <T, ID>  Mono<Long>    deleteById(Class<T> entityType, ID id);
    <T>      Mono<Long>    count(Class<T> entityType, QuerySpec querySpec);
    <T>      Mono<Boolean> exists(Class<T> entityType, QuerySpec querySpec);
    <R>      Mono<R>       inTransaction(Function<ReactiveEntityOperations, Mono<R>> callback);
}
```

`save`는 `EntityStateDetector`가 식별자 상태를 보고 **insert / update**를 자동으로 선택합니다.

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

지원 연산자: `eq`, `ne`, `gt`, `gte`, `lt`, `lte`, `like`, `isNull`, `isNotNull`,
그리고 이들을 묶는 `and` / `or`.

---

## Transactions

`ReactiveTransactionOperations`는 콜백을 트랜잭션 경계 안에서 실행하기 위한 최소 계약입니다.

```java
operations.inTransaction(tx ->
    tx.save(new Account(null, "a@example.com", true))
      .then(tx.save(new Account(null, "b@example.com", true)))
      .then(tx.count(Account.class, QuerySpec.empty()))
).subscribe();
```

- 콜백 내부에서 예외/`Mono.error`가 발생하면 **자동 롤백**됩니다.
- 트랜잭션 컨텍스트는 Reactor의 Context를 통해 전달되므로 스레드 누수에 안전합니다.

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

| Dialect              | Bind marker | Identity column                            | Quote |
|----------------------|-------------|--------------------------------------------|-------|
| `PostgresqlDialect`  | `$1`, `$2`  | `bigserial` / `serial` primary key         | `" "` |
| `MySqlDialect`       | `?`         | `bigint primary key auto_increment`        | `` ` ` `` |

새 다이얼렉트는 `AbstractSqlRenderer`와 `AbstractSchemaGenerator`를 확장해 핵심 차이만 오버라이드하면 됩니다.

---

## Schema generation

`SchemaGenerator`는 엔티티 메타데이터로부터 `CREATE TABLE` 문을 생성합니다.

```java
Dialect dialect = new MySqlDialect();
EntityMetadata metadata = metadataFactory.metadataFor(Account.class);

String ddl = dialect.schemaGenerator().createTable(metadata);
// → CREATE TABLE `accounts` (`id` bigint primary key auto_increment, ...)
```

개발 환경의 초기화 스크립트나 통합 테스트의 픽스처 준비에 그대로 사용할 수 있습니다.

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
    │       ├── annotation/                             # @Entity, @Id, @Table, @Column, @GeneratedValue ...
    │       ├── core/                                   # ReactiveEntityOperations, SqlExecutor, RowAccessor ...
    │       ├── convert/                                # AttributeConverter SPI
    │       ├── metadata/                               # EntityMetadata, NamingStrategy
    │       ├── query/                                  # Criteria, Predicate, QuerySpec, Sort, Pageable
    │       ├── sql/                                    # Dialect, SqlRenderer, SchemaGenerator ...
    │       └── tx/                                     # ReactiveTransactionOperations, TransactionContext
    ├── nova-r2dbc/                                     # R2DBC SPI 어댑터
    └── nova-dialects/
        ├── nova-dialect-postgresql/                    # PostgreSQL 다이얼렉트
        └── nova-dialect-mysql/                         # MySQL 다이얼렉트
```

> Maven 좌표는 평탄(`io.nova:nova-core`)하게 유지됩니다 — Gradle 경로만 `:nova-project:...`로 중첩.

---

## Roadmap

- [ ] 관계 매핑 (`@OneToMany`, `@ManyToOne`)
- [ ] Optimistic locking (`@Version`)
- [ ] Spring Boot 자동 구성 (`nova-spring-boot-starter`)
- [ ] H2 / MariaDB / Oracle 다이얼렉트
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
