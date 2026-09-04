<!-- SPDX-License-Identifier: Apache-2.0 -->

# JPA / jakarta.persistence compatibility

Nova maps entities with the **standard `jakarta.persistence` annotations** and aims for
*reactive feature equivalence* with JPA: every supported capability returns `Mono` / `Flux`
rather than blocking. Nova does **not** implement the blocking `jakarta.persistence.EntityManager`
contract literally — it provides a reactive equivalent (`ReactiveEntityManager`) that preserves
Nova's non-blocking contract.

## Java and Gradle compatibility

Published Nova artifacts target Java 17 bytecode and run on Java 17 and newer
compatible runtimes. Every change is built and tested in CI with Temurin 17,
21, 25, and 26. The checked-in Gradle Wrapper is used for all builds; Gradle
9.4 or newer is required when Gradle itself runs on Java 26. The CI matrix
checks the selected JDK as the actual Gradle runtime, so a pinned compilation
toolchain cannot hide a runtime incompatibility.

Two rules make the table below predictable:

- **Additive.** The reactive persistence API is unchanged; JPA annotations are read on top of it.
- **Fail-fast, never silent.** An unsupported annotation or combination is rejected at
  metadata-build time with a clear message. Nova never silently ignores a *mapping*. The only
  accepted-but-inert inputs are hints with no reactive meaning — `fetch = LAZY` / `EAGER`,
  `@Basic(fetch = ...)`, and `cascade = REFRESH` / `DETACH` — and each is called out where it applies.

Legend: **✅ supported** · **⟳ reactive-equivalent** (Mono/Flux instead of the blocking JPA type) ·
**⛔ fail-fast** (declared but rejected with a message until implemented).

---

## Entities, ids, and columns

| Feature | Status | Notes |
|---|---|---|
| `@Entity` / `@Table` / `@Column` | ✅ | `name` / `length` / `precision` / `scale` / `secondPrecision` / `insertable` / `updatable` / `nullable`; JPA 3.2 `check`, `comment`, and `options` are rendered for newly-created tables/columns |
| `@Id` + `@GeneratedValue` | ✅ | `IDENTITY`, `SEQUENCE`, `TABLE` (`@TableGenerator`), `AUTO` (maps to `IDENTITY`), `UUID` |
| `@Basic` | ✅ | `optional = false` enforced as `NOT NULL` (combines with `@Column(nullable)`); `fetch` is accepted but inert |
| `@EmbeddedId` / `@IdClass` composite keys | ✅ | `findById` / `deleteById` / soft-delete / batch-delete / optimistic + pessimistic lock |
| `@Embeddable` / `@Embedded` / `@AttributeOverride` | ✅ | Mutable and Java record value types are flattened into the owner table; otherwise-unmapped embeddable-typed attributes are implicit; nested outer overrides take precedence |
| `@Enumerated` (`STRING` / `ORDINAL`) | ✅ | |
| `@EnumeratedValue` (JPA 3.2) | ✅ | Enum constant field values are used as the stored representation; requires a supported `String`/numeric value type |
| `@Temporal` (`java.util.Date` / `Calendar`) | ✅ | `DATE` / `TIME` / `TIMESTAMP`; `java.time.*` supported natively |
| `@Lob` | ✅ | |
| `@Convert` + `jakarta.persistence.AttributeConverter` | ✅ | Storage-type driven read/write; managed converter classes support `autoApply`, explicit override, and disable semantics |
| Scalar types | ✅ | `UUID`, `Float`, `Short`, `BigDecimal`, `BigInteger`(driver-permitting), … — driver-verified. `BigDecimal` DDL preserves declared `@Column(precision, scale)` on scalar, id/FK, join-table, and collection-table storage; `@Column(columnDefinition)` is rejected on every physical `BigDecimal` storage column. MySQL/MariaDB reject only a fully unspecified shape and normalize scale-only to `decimal(65, scale)`. |
| `@Version` optimistic locking | ✅ | `Long` / `Integer` / `Short` / `LocalDateTime`; surfaces `OptimisticLockingFailureException` |
| `@Transient` | ✅ | Field annotations are excluded; under effective `@Access(PROPERTY)`, getter annotations are also excluded |
| `@Access(FIELD)` | ✅ | Default |
| `@Access(PROPERTY)` | ✅ | Basic **and** `@ManyToOne` / `@OneToOne` relations (JavaBean getter/setter) |
| `@SecondaryTable` / `@PrimaryKeyJoinColumn` | ✅ | `foreignKey` mode/name supported: `PROVIDER_DEFAULT` keeps the existing unnamed FK, `NO_CONSTRAINT` suppresses it, and an explicit name is quoted. These options apply only when creating a new secondary table; migrate existing secondary-table constraints externally. |
| Auditing (`@CreatedAt` / `@UpdatedAt`), lifecycle callbacks, `@EntityListeners` | ✅ | 7 lifecycle phases; listener + superclass inheritance; duplicate same-phase callbacks per declaring class fail fast |
| `@ExcludeSuperclassListeners` | ✅ | Excludes external listener hosts above the annotated entity or mapped-superclass host; entity callbacks remain inherited |
| `@ExcludeDefaultListeners` | ✅ | No-op without XML default listeners; explicit `@EntityListeners` and entity callbacks remain active |

## Inheritance

| Feature | Status | Notes |
|---|---|---|
| `@Inheritance(SINGLE_TABLE)` + `@DiscriminatorColumn` / `@DiscriminatorValue` | ✅ | |
| `@Inheritance(JOINED)` | ✅ | Polymorphic SELECT with derived-table wrapping |
| `@Inheritance(TABLE_PER_CLASS)` | ✅ | `IDENTITY` / `AUTO` ids are rejected because independent subtype identities can collide; use `TABLE` or `SEQUENCE`. |
| `@MappedSuperclass` | ✅ | Fields, ids, listeners inherited via ancestor walk |

## Relationships

| Feature | Status | Notes |
|---|---|---|
| `@ManyToOne` / owning `@OneToOne` | ✅ | FK column type aligned to the referenced `@Id` storage type; owning `@OneToOne(orphanRemoval = true)` supports replacement, nulling, and owner deletion |
| `@ManyToOne` / `@OneToOne` → **composite-key** target | ✅ | Multi-column FK (one column per referenced `@Id` component) + composite FK constraint; PERSIST-only cascade probes every converted key component, inserts an absent complete-key target before its constrained owner, and leaves an existing target unhydrated/unmodified; MERGE cascade updates it |
| inverse `@OneToOne` (`mappedBy`) | ✅ | Hydration only; `orphanRemoval` and mutating `PERSIST`/`MERGE`/`REMOVE`/`ALL` cascades fail fast |
| `@OneToMany` (`cascade`, `orphanRemoval`, `@OrderColumn`, `@OrderBy`) | ✅ | |
| `@ManyToMany` (owning + inverse, `cascade`, `@OrderBy`) | ✅ | Cycle-guarded cascade; join-table row diffing; owning + inverse delete cleanup; ordered `List` hydration; single-column IDs (including `UUID`) are encoded to referenced-`@Id` physical storage and decoded before lookup |
| `@ManyToMany` → **composite-key** owner/target | ✅ | Multi-column join table (composite PK + composite FK); PERSIST-only probes every id component, inserts an absent target before its link, and leaves an existing target unmodified; MERGE / ALL still save it. This is identical in stateless and persistence-session saves. |
| `@ElementCollection` | ✅ | Basic / enum / `UUID` elements, mutable and record `@Embeddable` values, `Map` keys/values (including records), `@OrderColumn`, `List` |
| `@MapKeyColumn` / `@MapKeyEnumerated` / `@MapKeyTemporal` / `@MapKeyClass` | ✅ | `@MapKeyClass` supports basic / enum / `@Embeddable` / single-`@Id` **entity** key classes (entity key stored as its `@Id` FK column, batch-hydrated); composite-`@Id` entity key classes fail-fast |
| `@MapsId` (whole `@Id`) | ✅ | |
| `@MapsId("component")` (one component of a composite `@Id`) | ✅ | Associated entity must have a single `@Id` |
| `@JoinColumn` / `@JoinColumns` / `@ForeignKey` | ✅ | Composite FK, constraint-name length bounds, idempotent `ddl-auto=UPDATE` |
| `@AssociationOverride` | ✅ | Remap the join column of an inherited to-one; overrides declared on an intermediate `@MappedSuperclass` are honored (most-derived declaration wins) |
| `cascade` on to-one (`PERSIST` / `MERGE` / `REMOVE`) | ✅ | Cycle-guarded; PERSIST-only does not update an existing complete composite-id target, while MERGE does |

## Fetching

| Feature | Status | Notes |
|---|---|---|
| Automatic relationship hydration (`FetchGroup`) | ✅ | Batched (one IN-query per association, no N+1); explicit FetchGroup/EntityGraph reads share transaction-bound identity and dirty tracking |
| Composite-key to-one eager hydration | ✅ | Batched via OR-of-ANDs predicate; hydrated as a leaf at nested `EntityGraph` subgraph depth too |
| `@NamedEntityGraph` / `EntityGraph` + JPQL `JOIN FETCH` | ✅ | Always-eager (graph ⊇ default) |
| Nested `@NamedSubgraph` (depth > 1) | ✅ | Recursive plan tree, per-level reactive batching; cycle fail-fast |
| `fetch = LAZY` | ✅ | Accepted (Nova batches rather than proxies) |

## Query languages

| Feature | Status | Notes |
|---|---|---|
| JPQL (`ReactiveEntityManager.createQuery`) | ⟳ | Hand-written lexer/parser/AST → SQL; injection-safe |
| JPQL `SELECT NEW` DTO, implicit joins, `LOCATE` / `CAST` / `FUNCTION` / `SIZE`, subqueries, bulk | ✅ | |
| JPQL `setFirstResult` / `setMaxResults` | ✅ | Negative values are rejected; zero max results completes without querying. Simple entity SELECTs use the dialect renderer, including offset-only requests as `Integer.MAX_VALUE` limits. Filtering-join entity SELECTs use SQL `DISTINCT` root IDs and page them reactively before incremental bounded (256-id) hydration chunks. Scalar, aggregate, and `SELECT NEW` projections preserve their SQL bindings and apply the window with reactive `skip` / `take` before result mapping because arbitrary JPQL SQL has no public dialect pagination hook. These client-side paths may read skipped rows from the database. |
| JPQL / Criteria `TREAT()` / `TYPE()` polymorphism | ✅ | `SINGLE_TABLE` / `JOINED` / `TABLE_PER_CLASS` (JOINED/TPC via the polymorphic derived table); discriminator-aware, shadowed-subtype-column fail-fast. Subquery positions fail-fast |
| Criteria API (`jakarta.persistence.criteria`) | ⟳ | Joins (M2O/O2O/O2M/inverse), subqueries (`EXISTS`/`IN`/correlate) |
| Criteria `ParameterExpression` | ✅ | Named and identity-bound scalar parameters execute across entity, scalar, join, and subquery routes. Values are validated and converted for the target column per subscription, then supplied as bind markers in SQL traversal order; explicit null bindings remain `?` / SQL `NULL` (they do not become `IS NULL`); positional, collection, missing, foreign, and type-conflicting non-null bindings fail fast. |
| Joins over a **composite-key** to-one target | ✅ | Multi-column `ON` (`a.c1=b.c1 AND a.c2=b.c2`) |
| Composite-key to-one in `WHERE` (`=` / `IS [NOT] NULL`, ordering `< <= > >=`, `IN`, `BETWEEN`), `GROUP BY` / `ORDER BY`, and scalar `SELECT` projection | ✅ | Scalar JPQL + Criteria; lexicographic multi-column expansion, single canonical component order. `IS NULL` requires every FK component null; `IS NOT NULL` accepts any non-null component, matching hydration. `SELECT c.parent` yields a target id-stub. `LIKE` / entity-returning-JPQL `WHERE` fail-fast |
| `@NamedQuery` / `@NamedNativeQuery` | ✅ | Per-entity registry, duplicate-name fail-fast |
| `@SqlResultSetMapping` (`@EntityResult` / `@FieldResult` / `@ConstructorResult` / `@ColumnResult`) | ✅ | Native-read-then-coerce (dialect-independent) |
| `@StoredProcedureQuery` / `@NamedStoredProcedureQuery` | ⟳ | Declared `IN` params + result sets; setters synchronously reject blank/unknown names, unknown 1-based positions, duplicate name/position addressing of one named slot, and incompatible non-null Java values (primitive declarations accept wrappers; `null` is valid). Duplicate declaration names fail at query construction. No coercion/default args/output API. R2DBC SPI 1.0 models `OUT`/`INOUT`/`REF_CURSOR`, but Nova executor/result APIs plus the H2 baseline lack portable support, so any such declaration fails before native work. |

## EntityManager / session

| Feature | Status | Notes |
|---|---|---|
| `ReactiveEntityManager` (`persist` / `merge` / `remove` / `find` / `getReference` / `flush` / `clear` / `detach` / `refresh` / `contains`) | ⟳ | `Nova.entityManager(...)`. `refresh` uses a fresh database read that bypasses shared entity-cache lookup and population without evicting warm entity or query-cache entries. It replaces only scalar, embedded, and FK-column state; relationship collections and fully hydrated graphs are not replaced, and `cascade = REFRESH` is inert. Refetch with `ReactiveEntityOperations.findById(..., FetchGroup)` or `findById(..., EntityGraph)` when an association graph must be read again (these plans guarantee named-association batching but remain always-eager, not selective graph replacement). |
| `LockModeType` (`PESSIMISTIC_WRITE`/`READ`, `OPTIMISTIC`, `FORCE_INCREMENT`) | ✅ | `find` / `lock` / `getLockMode` overloads. `getLockMode` is `NONE` for an ordinary managed find and reports the requested alias only after that exact managed instance successfully completes the lock. Detached stand-ins cannot observe or alter the canonical instance; failed/cancelled lock or lock-refresh attempts preserve its prior mode and state; successful lock-refresh records its requested mode. `remove`, `detach`, `clear`, and transaction completion reset/discard the state. |
| `FlushModeType` | ✅ | Propagated via Reactor `Context` |
| Transaction-bound persistence session (identity map + dirty checking + flush) | ✅ | Opt-in; collection diff-at-flush. A completed explicit partial update advances only the exact managed instance's written-field baseline, so unrelated dirty fields flush separately; detached same-id writes do not clean the canonical instance. A successful `remove` retains an internal tombstone until `clear`: it is excluded from scalar/collection flush and `contains`/lock management, and re-persisting that identity in the same session fails explicitly. Lifecycle remove callbacks run on subscription, with `@PostRemove` after successful DML. |
| 2nd-level cache (`nova-cache`, `@Cacheable` / `SharedCacheMode`) | ✅ | Each entity/query-cache hit is a fresh detached mapped graph: PROPERTY accessors, mapped converter/`@Temporal` values, and element collections are reconstructed while shared references and cycles are preserved only within that hit; unmapped state is not copied. Every successful wrapped write globally invalidates graph-bearing entity and query caches, including non-cacheable associated writes. Managed transactions bypass shared values and replay that invalidation only after a successful physical commit. |

## Spring

| Feature | Status | Notes |
|---|---|---|
| Spring Data-style `ReactiveCrudRepository<T, ID>` + `Pageable` / `Sort` | ✅ | `nova-spring-data`, opt-in `SpringDataReactiveCrudRepository` |
| `@Query` (JPQL) on repository methods | ✅ | `@EnableNovaRepositories`, `BeanFactoryAware` auto-wiring. `Mono<T>` is zero-or-one; non-unique results fail rather than truncate. Use derived `findFirst...` / `findTop...` for explicit one-row truncation. |

---

## Not yet supported (fail-fast)

These declare cleanly but are rejected with a message until implemented — Nova never mis-renders them:

- Composite-key to-one in a **`LIKE`** position, or in an **entity-returning JPQL `WHERE`** predicate
  (`SELECT c FROM C c WHERE c.parent < :x`). Use a scalar projection (`SELECT c.id … WHERE c.parent < :x`)
  or the Criteria API instead. Scalar `SELECT` projection, `WHERE` equality/`IS NULL`/ordering/`IN`/`BETWEEN`,
  `GROUP BY` / `ORDER BY`, and multi-column **joins** are supported.
- A deeper subgraph declared **under** a composite-key to-one leaf inside a nested `EntityGraph`
  (the composite leaf itself is now hydrated at any depth).
- Stored-procedure `OUT` / `INOUT` / `REF_CURSOR` output retrieval. R2DBC SPI 1.0 declarations are modeled, but Nova executor/result APIs plus the H2 baseline lack portable support; any such declaration fails before native work. Use `IN` parameters and a result set instead.
- `@MapKeyClass` naming a **composite-`@Id`** entity key class (single-`@Id` entity and `@Embeddable` key classes are supported).
- In-place mutation of a *loaded* referenced entity's `@Id` (JPA-forbidden) is not change-tracked.
- Under a transaction-bound persistence session, moving an already-managed `@OneToMany` child between two
  parents' collections (`a.getChildren().remove(x); b.getChildren().add(x);`) without also setting the
  child's own owning `@ManyToOne` field. Set the `@ManyToOne` side explicitly so its own dirty-check drives
  the foreign-key update; collection-membership-only reparenting is rejected rather than silently orphaning
  or deleting the child on the losing side.
- Under a session, removing a child from a non-`orphanRemoval` `@OneToMany` collection when the child's
  owning `@ManyToOne` foreign key is non-nullable (`optional = false` / `@JoinColumn(nullable = false)`) —
  nulling it would violate the column constraint. Use `orphanRemoval = true` or reparent explicitly instead.
- Nested `@EmbeddedId` values are rejected explicitly. A flat record `@EmbeddedId` supports
  `@MapsId("component")` under FIELD or PROPERTY access only when the associated entity has one
  `@Id`; whole-key `@MapsId`, nested record-id components, and composite-key association targets
  remain rejected. Ordinary nested record `@Embedded` values are supported.
- Owning `@OneToOne(orphanRemoval = true)` with `@MapsId` or a non-updatable join column is
  rejected. Support includes FIELD and PROPERTY access plus scalar/composite owner and target
  keys. The same-owner shared-reference guard is deliberately bounded; it does not discover
  soft references from arbitrary other entity types and is not a concurrent race guarantee.
- JPA 3.2 physical DDL members outside the supported `@Table`/`@Column` set (for example provider-specific schema-generation controls) remain unsupported and are rejected where Nova can detect them.

> Composite `@Id` components should be round-trip-stable types (integers, `String`, `UUID`, enums).
> Types whose stored form does not decode back byte-for-byte (`BigDecimal` scale drift, sub-second
> timestamp precision) are not recommended as key components. `BigDecimal.equals` is scale-sensitive
> (`new BigDecimal("1.0")` is not equal to `new BigDecimal("1.00")`), although their numeric values
> compare equal. A database or driver may normalize the scale during a round trip, so do not use a
> `BigDecimal` whose scale identity matters as an id, composite-id component, or relationship key;
> use a round-trip-stable key and `compareTo` for numeric business equality.

For status and history of the parity work, see the module changelog / release notes (`v2.0.0`–`v2.31.0`).
