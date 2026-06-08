<!-- SPDX-License-Identifier: Apache-2.0 -->

# Getting started

Nova `1.0.1`은 Maven Central에서 받을 수 있습니다 (`1.0.0` GA 이후 최신 릴리스).

## 1. 의존성 추가

가장 빠른 진입은 aggregate 모듈 `io.github.bssm-oss:nova` + 사용할 DB의 **R2DBC 드라이버** 조합입니다. aggregate는 core/r2dbc 어댑터/모든 번들 dialect(PostgreSQL/MySQL/MariaDB/H2/Oracle)를 한 번에 끌어옵니다.

```kotlin
// build.gradle.kts
repositories {
    mavenCentral()
}

dependencies {
    implementation("io.github.bssm-oss:nova:1.0.1")

    // 사용할 데이터베이스의 R2DBC 드라이버 (택1)
    runtimeOnly("io.r2dbc:r2dbc-h2:1.0.0.RELEASE")
    // runtimeOnly("org.postgresql:r2dbc-postgresql:1.0.7.RELEASE")
    // runtimeOnly("io.asyncer:r2dbc-mysql:1.3.0")
}
```

특정 dialect만 골라 쓰고 싶다면 aggregate 대신 `nova-core` + `nova-r2dbc` + 원하는 dialect 모듈을 직접 선언해도 됩니다.

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

> **Snapshot 사용**: 다음 dev 빌드(`1.0.2-SNAPSHOT` 등)는 Central snapshots 저장소에서 받을 수 있습니다.
>
> ```kotlin
> repositories {
>     mavenCentral()
>     maven("https://central.sonatype.com/repository/maven-snapshots/")
> }
> ```

## 2. 엔티티 정의

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

전체 어노테이션 레퍼런스는 [Entities](entities.md)를 참고하세요.

## 3. 작업 실행

`Nova.create(connectionFactory)`가 R2DBC driver metadata로 dialect를 자동 감지(PostgreSQL/MySQL/MariaDB/H2/Oracle)해 `ReactiveEntityOperations`를 조립합니다. 매핑되지 않는 driver는 `Nova.create(cf, dialect)`로 dialect를 직접 주입하세요.

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

## 4. 스키마 초기화 (선택)

운영에서는 Flyway/Liquibase 같은 마이그레이션 도구를 권장하지만, 통합 테스트나 데모 시드용으로 `SchemaGenerator`가 발행한 DDL을 그대로 실행할 수 있습니다.

```java
import io.nova.metadata.DefaultNamingStrategy;
import io.nova.metadata.EntityMetadataFactory;
import io.nova.query.NativeQuery;
import io.nova.sql.Dialect;

EntityMetadataFactory metadataFactory = new EntityMetadataFactory(new DefaultNamingStrategy());
Dialect dialect = Nova.resolveDialect(cf);

String ddl = dialect.schemaGenerator()
        .createTable(metadataFactory.getEntityMetadata(Account.class));

operations.executeNative(NativeQuery.of(ddl)).block();
```

자세한 schema generation API는 [Dialects & Schema](dialects.md)를 참고.

## Spring Boot 환경

Spring Boot에서는 [`nova-spring-boot-starter`](spring.md)가 `ConnectionFactory` 빈을 보고 dialect를 자동 감지해 `ReactiveEntityOperations` 빈을 등록합니다. 별도 `Nova.create(...)` 호출이 필요 없습니다.

## 다음 단계

- [Entities](entities.md) — 어노테이션, 관계 매핑, composite types, 인덱스
- [Queries](queries.md) — Query DSL, Updater, Projection, Aggregations, Pagination
- [Transactions](transactions.md) — Propagation, pessimistic locking, retry
- [Spring](spring.md) — Spring Boot starter, Spring Data 스타일 Repository
