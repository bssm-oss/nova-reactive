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
넌블로킹 데이터 파이프라인에 자연스럽게 녹아듭니다. 풀스택 ORM의 영속성 컨텍스트·1차 캐시·
지연 로딩 복잡도를 피하고, 코어 모듈은 R2DBC SPI에만 의존합니다.

> **Nova is not** a streaming framework, a query builder for arbitrary SQL, or a drop-in
> replacement for JPA. 작은 표면적의 리액티브 데이터 액세스 계층이 목표입니다.

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
@Table("accounts")
public class Account {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column("email_address") private String email;
    public Account() {}
    public Account(Long id, String email) { this.id = id; this.email = email; }
    // getters/setters...
}

ConnectionFactory cf = ConnectionFactories.get(
        "r2dbc:h2:mem:///nova-smoke?options=DB_CLOSE_DELAY=-1");
ReactiveEntityOperations operations = Nova.create(cf);

operations.save(new Account(null, "user@example.com"))
          .flatMap(saved -> operations.findById(Account.class, saved.getId()))
          .subscribe(System.out::println);
```

`Nova.create(cf)`는 R2DBC driver metadata로 dialect를 자동 감지(PostgreSQL/MySQL/MariaDB/H2/Oracle)합니다. 자세한 셋업·스키마 초기화·Spring Boot 환경은 [Getting started](docs/getting-started.md) 참고.

---

## Documentation

| Document                                       | Contents                                                                |
|------------------------------------------------|-------------------------------------------------------------------------|
| [Getting started](docs/getting-started.md)     | 설치, 첫 엔티티, `Nova.create(...)`, 스키마 초기화                         |
| [Entities](docs/entities.md)                   | 어노테이션, composite types, 관계 매핑, 인덱스                              |
| [Queries](docs/queries.md)                     | CRUD, Query DSL, Updater, Projection, Aggregations, Page/Slice, Cursor   |
| [Transactions](docs/transactions.md)           | Propagation/Isolation, pessimistic locking, retry                        |
| [Dialects & Schema](docs/dialects.md)          | `Dialect` SPI, 5개 번들 dialect, `SchemaGenerator`, migration helpers     |
| [Spring](docs/spring.md)                       | Spring Boot starter, `nova-spring-data` Repository                       |
| [Observability](docs/observability.md)         | `SqlExecutionListener`, Micrometer 어댑터, pool reachability             |

전체 문서 인덱스는 [`docs/`](docs/README.md).

---

## Requirements

|                  | Version                         | Notes                          |
|------------------|---------------------------------|--------------------------------|
| Java             | 21                              | Gradle Toolchains 자동 해석     |
| Build            | Gradle 8.10+                    | Wrapper(`./gradlew`) 동봉      |
| Reactive Runtime | Project Reactor 3.7.x           |                                |
| Driver SPI       | R2DBC SPI 1.0.0.RELEASE         |                                |
| PostgreSQL       | 14, 15, 16, 17                  | `nova-dialect-postgresql`      |
| MySQL            | 8.0, 8.4                        | `nova-dialect-mysql`           |
| MariaDB          | 10.5+, 11                       | `nova-dialect-mariadb`         |
| H2               | 2.x                             | `nova-dialect-h2`              |
| Oracle           | 12c+, 19c, 21c+                 | `nova-dialect-oracle`          |

Nova 코어는 R2DBC **SPI**에만 의존합니다. 실제 사용 시에는 데이터베이스에 맞는 R2DBC 드라이버(`r2dbc-postgresql`, `r2dbc-mysql` 등)를 별도로 의존성에 추가하세요.

---

## Modules

| Module                       | Description                                                                  |
|------------------------------|------------------------------------------------------------------------------|
| `nova`                       | aggregate — core + r2dbc + 모든 번들 dialect                                  |
| `nova-core`                  | 어노테이션, 메타데이터, 쿼리 DSL, SQL 렌더링, 트랜잭션, `ReactiveEntityOperations` 등 핵심 추상화 |
| `nova-r2dbc`                 | R2DBC SPI 어댑터 — `R2dbcSqlExecutor`, `R2dbcTransactionManager`, `SqlExecutionListener` hook |
| `nova-dialect-postgresql`    | PostgreSQL 다이얼렉트 (`$N` bind marker, `bigserial`, `RETURNING`)             |
| `nova-dialect-mysql`         | MySQL 다이얼렉트 (`?` bind marker, `auto_increment`)                            |
| `nova-dialect-h2`            | H2 다이얼렉트 (`GENERATED ALWAYS AS IDENTITY`)                                  |
| `nova-dialect-mariadb`       | MariaDB 다이얼렉트                                                              |
| `nova-dialect-oracle`        | Oracle 다이얼렉트 (`OFFSET..FETCH`, `<seq>.nextval from dual`)                  |
| `nova-spring-boot-starter`   | Spring Boot 자동 구성 — dialect 자동 감지, `nova.*` properties                  |
| `nova-spring-data`           | Spring Data 스타일 `ReactiveCrudRepository<T, ID>` + `@EnableNovaRepositories` |
| `nova-metrics-micrometer`    | Micrometer 어댑터 (`MicrometerSqlExecutionListener`)                            |

Maven 좌표는 `io.github.bssm-oss:<module>:1.0.1` 형태로 평탄하게 유지됩니다.

---

## Building from source

```bash
./gradlew build          # 전체 빌드 + 테스트
./gradlew test           # 테스트만 실행
./gradlew clean build    # 깨끗하게 다시 빌드
```

Gradle Wrapper(`./gradlew`)가 동봉되어 있어 별도 Gradle 설치가 필요 없습니다.

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
- [x] H2 / MariaDB / Oracle 다이얼렉트
- [x] Spring Boot 자동 구성 (`nova-spring-boot-starter`) + dialect 자동 감지
- [x] Pessimistic locking (`QuerySpec.forUpdate()` / `forShare()`)
- [x] Metrics 어댑터 (`nova-metrics-micrometer` — Micrometer)
- [x] Retry helper (`ReactiveRetryTemplate` — exponential backoff + jitter)
- [x] FetchGroup DSL + 관계 매핑 (`@ManyToOne` / `@OneToMany` 자동 hydration)
- [x] Page / Slice 결과 타입 + `PageRequest` page-number 친화 API
- [x] Spring Data 스타일 Repository (`nova-spring-data`, Spring Data Commons 미의존)
- [x] JSON column type (`@Json` — pluggable `JsonCodec` SPI)
- [x] `@Column(length / precision / scale)` + `BigDecimal` 컬럼 지원
- [x] 1.0 GA 릴리스 및 Maven Central 배포 (`io.github.bssm-oss:nova:1.0.0` — 11 modules 전부 Central 공개)

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
