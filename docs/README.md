<!-- SPDX-License-Identifier: Apache-2.0 -->

# Nova documentation

Detailed documentation for Nova. For a quick start, see the root [`README.md`](../README.md).

## Guides

| Document                              | Contents                                                                        |
|---------------------------------------|---------------------------------------------------------------------------------|
| [Getting started](getting-started.md) | Installation, first entity, `Nova.create(...)`, schema initialization            |
| [Entities](entities.md)               | Annotation reference, composite types (`@Embeddable`), relationships, indexes    |
| [JPA compatibility](jpa-compatibility.md) | `jakarta.persistence` feature matrix — supported / reactive-equivalent / fail-fast |
| [Queries](queries.md)                 | CRUD, Query DSL, Updater, Projection, Aggregations, Page/Slice, Cursor, NativeQuery, CompiledQuery |
| [Transactions](transactions.md)       | `inTransaction`, Propagation / Isolation / readOnly, pessimistic locking, retry  |
| [Dialects & Schema](dialects.md)      | `Dialect` interface, the five bundled dialects, `SchemaGenerator`, alter helpers |
| [Spring](spring.md)                   | Spring Boot starter (auto-detect, properties), `nova-spring-data` repositories   |
| [Observability](observability.md)     | `SqlExecutionListener`, Micrometer adapter, pool reachability probe              |
| [Metamodel](metamodel.md)             | `nova-metamodel` annotation processor for compile-time property-name constants   |

## API entry points

- `io.nova.Nova` — one-line entry point taking a `ConnectionFactory` (dialect auto-detected from driver metadata)
- `io.nova.core.ReactiveEntityOperations` — every persistence API
- `io.nova.query.Criteria`, `io.nova.query.QuerySpec` — Query DSL
- `io.nova.sql.Dialect`, `io.nova.sql.SchemaGenerator` — dialect extension SPI
- `io.nova.core.SqlExecutionListener` — SQL execution observation hook

## Module coordinates (Maven Central)

All modules under the `io.github.bssm-oss` group are published at the same version (currently `2.8.0`).

```
io.github.bssm-oss:nova                          # aggregate (core + r2dbc + all dialects)
io.github.bssm-oss:nova-core
io.github.bssm-oss:nova-r2dbc
io.github.bssm-oss:nova-dialect-postgresql
io.github.bssm-oss:nova-dialect-mysql
io.github.bssm-oss:nova-dialect-mariadb
io.github.bssm-oss:nova-dialect-h2
io.github.bssm-oss:nova-dialect-oracle
io.github.bssm-oss:nova-spring-boot-starter
io.github.bssm-oss:nova-spring-data
io.github.bssm-oss:nova-metrics-micrometer
io.github.bssm-oss:nova-metamodel                # opt-in annotation processor (compile-time only)
io.github.bssm-oss:nova-cache                    # opt-in 2nd-level cache
```
