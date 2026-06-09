<!-- SPDX-License-Identifier: Apache-2.0 -->

# Dialects & Schema

`Dialect` is the single interface that encapsulates per-database differences.

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
| `PostgresqlDialect`  | `$1`, `$2`  | `bigserial` / `serial` primary key                              | `" "`      | `RETURNING` clause        | `nextval('seq')`         |
| `MySqlDialect`       | `?`         | `bigint primary key auto_increment`                             | `` ` ` ``  | `Statement.returnGeneratedValues` | not supported (UOE) |
| `H2Dialect`          | `?`         | `bigint generated always as identity primary key`               | `" "`      | `Statement.returnGeneratedValues` (driver-side) | not supported (UOE) |
| `MariaDbDialect`     | `?`         | `bigint primary key auto_increment`                             | `` ` ` ``  | `Statement.returnGeneratedValues` | not supported (UOE) |
| `OracleDialect`      | `?`         | `number(19) generated always as identity primary key`           | `" "`      | `Statement.returnGeneratedValues` | `<seq>.nextval from dual` |

> **Oracle specifics**: there is no `LIMIT/OFFSET`, so pagination renders as `OFFSET ? ROWS FETCH NEXT ? ROWS ONLY` and `exists()` renders as `FETCH FIRST 1 ROWS ONLY`. `FOR SHARE` row locking is unsupported and throws `UnsupportedOperationException`. `@Json` columns map to `clob` (override per-dialect for native `JSON` on 21c+).

For `@GeneratedValue(strategy = SEQUENCE, generator = "account_seq")`, Nova issues a SELECT aliased as `Dialect.SEQUENCE_VALUE_COLUMN` using the dialect's `sequenceNextValueSql(generator)` to fetch the id beforehand. For `UUID`, ops stamp `UUID.randomUUID()` just before INSERT for `java.util.UUID` or `String` fields.

A new dialect extends `AbstractSqlRenderer` and `AbstractSchemaGenerator` and overrides only the differences.

---

## Schema generation

`SchemaGenerator` produces a `CREATE TABLE` statement from entity metadata.

```java
Dialect dialect = new MySqlDialect();
EntityMetadata<Account> metadata = metadataFactory.getEntityMetadata(Account.class);

String ddl = dialect.schemaGenerator().createTable(metadata);
// → CREATE TABLE `accounts` (`id` bigint primary key auto_increment, ...)
```

Use it directly for dev-environment bootstrap scripts and integration-test fixtures.

---

## Idempotent DDL

`createTableIfNotExists` and `dropTableIfExists` emit idempotent variants of the standard DDL — useful when bootstrapping a dev/test schema that may already exist:

```java
String safeCreate = dialect.schemaGenerator().createTableIfNotExists(metadata);
// PostgreSQL / MySQL / MariaDB / H2: "create table if not exists ..."
String safeDrop = dialect.schemaGenerator().dropTableIfExists(metadata);
// "drop table if exists ..."
```

**Oracle caveat**: Oracle has no `IF [NOT] EXISTS` syntax on `CREATE TABLE` / `DROP TABLE`. `OracleSchemaGenerator` wraps the raw DDL in a PL/SQL anonymous block that swallows `ORA-00955` (object already exists) on create and `ORA-00942` (table or view does not exist) on drop, re-raising any other error. The `dropTableIfExists` variant also appends `purge` so the recycle bin stays clean and a follow-up `CREATE TABLE` of the same name does not collide.

```sql
-- Oracle dropTableIfExists output (formatted)
begin
  execute immediate 'drop table "accounts" purge';
exception
  when others then
    if sqlcode != -942 then raise; end if;
end;
```

For high-level orchestration (multi-entity create / drop / recreate), use [`SchemaInitializer`](../README.md) — `Nova.schemaInitializer(cf)` or the Spring Boot `nova.ddl-auto` property.

---

## Schema migration

Alongside `createTable`, `SchemaGenerator` ships lightweight DDL helpers for migration. All of them are implemented by the dialect modules (`AbstractSchemaGenerator`); unsupported dialects throw `UnsupportedOperationException`.

```java
SchemaGenerator schema = dialect.schemaGenerator();
EntityMetadata<Account> metadata = metadataFactory.getEntityMetadata(Account.class);

// 1) Table + every @Index / @UniqueConstraint in one go
String createTable = schema.createTable(metadata);
List<String> indexDdls = schema.createIndexes(metadata);
// indexDdls example: ["create index \"ix_accounts_email\" on \"accounts\" (\"email\")",
//                     "create unique index \"uk_accounts_tenant_id_email\" on \"accounts\" (\"tenant_id\", \"email\")"]

// 2) Add a column — reuses the entity metadata's nullable / identity / default column type rules
PersistentProperty newColumn = metadata.findProperty("nickname").orElseThrow();
String addSql = schema.alterTableAddColumn(metadata, newColumn);
// → alter table "accounts" add column "nickname" varchar(255)

// 3) Drop a column — fail-fast IllegalArgumentException if the column is not in metadata
String dropSql = schema.alterTableDropColumn(metadata, "legacy_flag");
// → alter table "accounts" drop column "legacy_flag"
```

Run the emitted DDL via `executeNative(NativeQuery.of(ddl))` through the R2DBC adapter, or hand it off as input to a migration tool such as Flyway.

- The default `createIndexes` returns an empty list, but `AbstractSchemaGenerator` (the base for every bundled dialect) generates real DDL.
- `alterTableDropColumn` cross-checks the column name against metadata to prevent typos from emitting a bogus DROP.
