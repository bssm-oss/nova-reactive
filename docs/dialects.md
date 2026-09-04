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

| Dialect              | Bind marker | IDENTITY / AUTO column                                          | Quote      | Generated keys           | SEQUENCE                |
|----------------------|-------------|-----------------------------------------------------------------|------------|--------------------------|--------------------------|
| `PostgresqlDialect`  | `$1`, `$2`  | `bigserial` / `serial` primary key                              | `" "`      | `RETURNING` clause        | `nextval('seq')`         |
| `MySqlDialect`       | `?`         | `bigint primary key auto_increment`                             | `` ` ` ``  | `Statement.returnGeneratedValues` | not supported (UOE) |
| `H2Dialect`          | `?`         | `bigint generated always as identity primary key`               | `" "`      | `Statement.returnGeneratedValues` (driver-side) | not supported (UOE) |
| `MariaDbDialect`     | `?`         | `bigint primary key auto_increment`                             | `` ` ` ``  | `Statement.returnGeneratedValues` | not supported (UOE) |
| `OracleDialect`      | `?`         | `number(19) generated always as identity primary key`           | `" "`      | `Statement.returnGeneratedValues` | `<seq>.nextval from dual` |

> **Oracle specifics**: there is no `LIMIT/OFFSET`, so pagination renders as `OFFSET ? ROWS FETCH NEXT ? ROWS ONLY` and `exists()` renders as `FETCH FIRST 1 ROWS ONLY`. `FOR SHARE` row locking is unsupported and throws `UnsupportedOperationException`. `@Json` columns map to `clob` (override per-dialect for native `JSON` on 21c+).

`@GeneratedValue(strategy = AUTO)` and bare `@GeneratedValue` use the `IDENTITY / AUTO column` and generated-key path shown above for the active dialect. For `@GeneratedValue(strategy = SEQUENCE, generator = "account_seq")`, Nova issues a SELECT aliased as `Dialect.SEQUENCE_VALUE_COLUMN` using the dialect's `sequenceNextValueSql(generator)` to fetch the id beforehand. For `UUID`, ops stamp `UUID.randomUUID()` just before INSERT for `java.util.UUID` or `String` fields.

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

## BigDecimal DDL

`@Column(precision = p, scale = s)` is preserved as a requested physical decimal
shape; Nova does not silently substitute a currency default or discard a supplied scale.
The bundled dialect matrix is:

| Dialect | `precision = 0, scale = 0` | `precision > 0` | Scale only (`precision = 0, scale > 0`) | Bounds / failures |
|---|---|---|---|---|
| PostgreSQL | `numeric` | `numeric(p, s)` | `numeric(1000, s)` | `p` / `s` 0–1000; when `p > 0`, `s ≤ p` |
| MySQL | Fail-fast | `decimal(p, s)` | `decimal(65, s)` | `p` 1–65; `s` 0–30 and `s ≤ p` when `p` is explicit |
| MariaDB | Fail-fast | `decimal(p, s)` | `decimal(65, s)` | `p` 1–65; `s` 0–38 and `s ≤ p` when `p` is explicit |
| H2 | `decfloat` | `numeric(p, s)` | `numeric(100000, s)` | `p` / `s` 0–100000; when `p > 0`, `s ≤ p` |
| Oracle | `number` | `number(p, s)` (or `number(p, 0)`) | `number(*, s)` | `p` 0–38; `s` -84–127 |

For MySQL and MariaDB, only a shape with both precision and scale unspecified is rejected.
A scale-only declaration is normalized to the server maximum precision (`decimal(65, s)`),
so its requested fractional scale is retained without accepting a server-specific implicit
`DECIMAL` shape that can round or truncate values. PostgreSQL uses the corresponding
`numeric(1000, s)` normalization. H2 and Oracle have native unbounded/variable-scale forms,
so their unspecified and scale-only forms are shown explicitly in the matrix. H2's
unannotated `decfloat` has variable scale; it preserves the numeric value, but neither it
nor a driver round trip promises `BigDecimal.equals` scale identity.

The same matrix applies wherever Nova emits a `BigDecimal` storage column: scalar and
embedded properties (including secondary tables), primary and identity ids, to-one and
`@MapsId` FK columns, owner and target columns in `@ManyToMany` link tables, and
`@ElementCollection` owner-FK, basic/embedded element, map-key, and map-value columns.
This is a no-loss propagation policy: generated related columns retain the referenced
storage type, precision, and scale rather than falling back to a generic decimal shape.

Nova rejects `@Column(columnDefinition = ...)` for every physical `BigDecimal` storage
column, including an overridden embedded component. A raw SQL type cannot supply the
precision and scale required to guarantee exact reuse by a future or derived column. Nova
also rejects `columnDefinition` on relationship storage: `@JoinColumn`, `@JoinColumns`,
`@MapKeyJoinColumn`, nested `@JoinTable` join/inverse join columns, and secondary-table
primary-key joins. Use `precision`/`scale` for all generated decimal storage, or own every
affected column and FK in an external migration.

---

## Idempotent DDL

`createTableIfNotExists` and `dropTableIfExists` emit idempotent variants of the standard DDL — useful when bootstrapping a dev/test schema that may already exist:

```java
String safeCreate = dialect.schemaGenerator().createTableIfNotExists(metadata);
// PostgreSQL / MySQL / MariaDB / H2: "create table if not exists ..."
String safeDrop = dialect.schemaGenerator().dropTableIfExists(metadata);
// "drop table if exists ..."
```

**Oracle caveat**: Oracle has no `IF [NOT] EXISTS` syntax on `CREATE TABLE` / `DROP TABLE`. `OracleSchemaGenerator` wraps the raw DDL in a PL/SQL anonymous block that swallows `ORA-00955` (object already exists) on create and `ORA-00942` (table or view does not exist) on drop, re-raising any other error. The `dropTableIfExists` variant also appends `purge` so the recycle bin stays clean and a follow-up `CREATE TABLE` of the same name does not collide. When `@Table(schema = ...)` is present, its drop target remains schema-qualified and quoted (for example, `"audit"."accounts"`).

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

Run the emitted DDL via `executeNative(NativeQuery.of(ddl))` through the R2DBC adapter, or hand it off as input to a migration tool such as Flyway. These helpers do not reconcile an existing table's column type, precision, scale, or FK definitions. When a decimal shape changes (or a raw `columnDefinition` is used), write an external migration that alters/rebuilds every affected column and compatible FK/link-table constraint; review existing values for rounding or range failures before applying it.

- The default `createIndexes` returns an empty list, but `AbstractSchemaGenerator` (the base for every bundled dialect) generates real DDL.
- `alterTableDropColumn` cross-checks the column name against metadata to prevent typos from emitting a bogus DROP.
