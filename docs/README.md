<!-- SPDX-License-Identifier: Apache-2.0 -->

# Nova documentation

Nova의 상세 문서입니다. 빠른 시작은 루트 [`README.md`](../README.md)를 참고하세요.

## Guides

| Document                              | Contents                                                                        |
|---------------------------------------|---------------------------------------------------------------------------------|
| [Getting started](getting-started.md) | 설치, 첫 엔티티, `Nova.create(...)` 진입, 스키마 초기화                            |
| [Entities](entities.md)               | 어노테이션 레퍼런스, composite types (`@Embeddable`), 관계 매핑, 인덱스             |
| [Queries](queries.md)                 | CRUD, Query DSL, Updater, Projection, Aggregations, Page/Slice, Cursor, NativeQuery, CompiledQuery |
| [Transactions](transactions.md)       | `inTransaction`, Propagation/Isolation/readOnly, pessimistic locking, retry      |
| [Dialects & Schema](dialects.md)      | `Dialect` 인터페이스, 5개 번들 dialect, `SchemaGenerator`, alter helpers           |
| [Spring](spring.md)                   | Spring Boot starter (자동 감지, properties), `nova-spring-data` Repository        |
| [Observability](observability.md)     | `SqlExecutionListener`, Micrometer 어댑터, pool reachability probe                |

## API 진입점

- `io.nova.Nova` — `ConnectionFactory` 한 줄 진입점 (driver metadata로 dialect 자동 감지)
- `io.nova.core.ReactiveEntityOperations` — 모든 영속성 API
- `io.nova.query.Criteria`, `io.nova.query.QuerySpec` — Query DSL
- `io.nova.sql.Dialect`, `io.nova.sql.SchemaGenerator` — dialect 확장 SPI
- `io.nova.core.SqlExecutionListener` — SQL 실행 관찰 hook

## 모듈 좌표 (Maven Central)

`io.github.bssm-oss` 그룹의 모든 모듈은 동일 버전(현재 `1.0.1`)으로 publishing됩니다.

```
io.github.bssm-oss:nova                          # aggregate (core + r2dbc + 모든 dialect)
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
```
