<!-- SPDX-License-Identifier: Apache-2.0 -->

# Dialects & Schema

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

## Supported dialects

| Dialect              | Bind marker | Identity column                                                 | Quote      | Generated keys           | SEQUENCE                |
|----------------------|-------------|-----------------------------------------------------------------|------------|--------------------------|--------------------------|
| `PostgresqlDialect`  | `$1`, `$2`  | `bigserial` / `serial` primary key                              | `" "`      | `RETURNING` 절            | `nextval('seq')`         |
| `MySqlDialect`       | `?`         | `bigint primary key auto_increment`                             | `` ` ` ``  | `Statement.returnGeneratedValues` | 미지원 (UOE)     |
| `H2Dialect`          | `?`         | `bigint generated always as identity primary key`               | `" "`      | `Statement.returnGeneratedValues` (driver-side) | 미지원 (UOE)             |
| `MariaDbDialect`     | `?`         | `bigint primary key auto_increment`                             | `` ` ` ``  | `Statement.returnGeneratedValues` | 미지원 (UOE)     |
| `OracleDialect`      | `?`         | `number(19) generated always as identity primary key`           | `" "`      | `Statement.returnGeneratedValues` | `<seq>.nextval from dual` |

> **Oracle 특이사항**: `LIMIT/OFFSET` 미지원 → 페이지네이션은 `OFFSET ? ROWS FETCH NEXT ? ROWS ONLY`, `exists()`는 `FETCH FIRST 1 ROWS ONLY`로 렌더됩니다. `FOR SHARE` row lock은 미지원이라 `UnsupportedOperationException`을 던지고, `@Json` 컬럼은 `clob`로 매핑됩니다(21c+ native `JSON`은 dialect override).

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
