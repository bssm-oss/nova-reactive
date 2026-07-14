<!-- SPDX-License-Identifier: Apache-2.0 -->

# JPA / jakarta.persistence compatibility

Nova maps entities with the **standard `jakarta.persistence` annotations** and aims for
*reactive feature equivalence* with JPA: every supported capability returns `Mono` / `Flux`
rather than blocking. Nova does **not** implement the blocking `jakarta.persistence.EntityManager`
contract literally — it provides a reactive equivalent (`ReactiveEntityManager`) that preserves
Nova's non-blocking contract.

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
| `@Entity` / `@Table` / `@Column` | ✅ | `name` / `length` / `precision` / `scale` / `insertable` / `updatable` / `nullable` |
| `@Id` + `@GeneratedValue` | ✅ | `IDENTITY`, `SEQUENCE`, `TABLE` (`@TableGenerator`), `AUTO` (maps to `IDENTITY`), `UUID` |
| `@Basic` | ✅ | `optional = false` enforced as `NOT NULL` (combines with `@Column(nullable)`); `fetch` is accepted but inert |
| `@EmbeddedId` / `@IdClass` composite keys | ✅ | `findById` / `deleteById` / soft-delete / batch-delete / optimistic + pessimistic lock |
| `@Embeddable` / `@Embedded` / `@AttributeOverride` | ✅ | Nested value types flattened into the owner table |
| `@Enumerated` (`STRING` / `ORDINAL`) | ✅ | |
| `@Temporal` (`java.util.Date` / `Calendar`) | ✅ | `DATE` / `TIME` / `TIMESTAMP`; `java.time.*` supported natively |
| `@Lob` | ✅ | |
| `@Convert` + `jakarta.persistence.AttributeConverter` | ✅ | Storage-type driven read/write |
| Scalar types | ✅ | `UUID`, `Float`, `Short`, `BigDecimal`, `BigInteger`(driver-permitting), … — driver-verified |
| `@Version` optimistic locking | ✅ | `Long` / `Integer` / `Short` / `LocalDateTime`; surfaces `OptimisticLockingFailureException` |
| `@Access(FIELD)` | ✅ | Default |
| `@Access(PROPERTY)` | ✅ | Basic **and** `@ManyToOne` / `@OneToOne` relations (JavaBean getter/setter) |
| `@SecondaryTable` / `@PrimaryKeyJoinColumn` | ✅ | |
| Auditing (`@CreatedAt` / `@UpdatedAt`), lifecycle callbacks, `@EntityListeners` | ✅ | 7 lifecycle phases; listener + superclass inheritance |

## Inheritance

| Feature | Status | Notes |
|---|---|---|
| `@Inheritance(SINGLE_TABLE)` + `@DiscriminatorColumn` / `@DiscriminatorValue` | ✅ | |
| `@Inheritance(JOINED)` | ✅ | Polymorphic SELECT with derived-table wrapping |
| `@Inheritance(TABLE_PER_CLASS)` | ✅ | |
| `@MappedSuperclass` | ✅ | Fields, ids, listeners inherited via ancestor walk |

## Relationships

| Feature | Status | Notes |
|---|---|---|
| `@ManyToOne` / owning `@OneToOne` | ✅ | FK column type aligned to the referenced `@Id` storage type |
| `@ManyToOne` / `@OneToOne` → **composite-key** target | ✅ | Multi-column FK (one column per referenced `@Id` component) + composite FK constraint |
| inverse `@OneToOne` (`mappedBy`) | ✅ | |
| `@OneToMany` (`cascade`, `orphanRemoval`, `@OrderColumn`, `@OrderBy`) | ✅ | |
| `@ManyToMany` (owning + inverse, `cascade`) | ✅ | Join-table row diffing; owning + inverse delete cleanup |
| `@ManyToMany` → **composite-key** owner/target | ✅ | Multi-column join table (composite PK + composite FK) |
| `@ElementCollection` | ✅ | Basic / enum / `UUID` elements, `@Embeddable` elements, `Map`, `@OrderColumn`, `List` |
| `@MapKeyColumn` / `@MapKeyEnumerated` / `@MapKeyTemporal` / `@MapKeyClass` | ✅ | `@MapKeyClass` restricted to basic/enum key classes |
| `@MapsId` (whole `@Id`) | ✅ | |
| `@MapsId("component")` (one component of a composite `@Id`) | ✅ | Associated entity must have a single `@Id` |
| `@JoinColumn` / `@JoinColumns` / `@ForeignKey` | ✅ | Composite FK, constraint-name length bounds, idempotent `ddl-auto=UPDATE` |
| `@AssociationOverride` | ✅ | Remap the join column of an inherited to-one |
| `cascade` on to-one (`PERSIST` / `MERGE` / `REMOVE`) | ✅ | Cycle-guarded |

## Fetching

| Feature | Status | Notes |
|---|---|---|
| Automatic relationship hydration (`FetchGroup`) | ✅ | Batched (one IN-query per association, no N+1) |
| Composite-key to-one eager hydration | ✅ | Batched via OR-of-ANDs predicate |
| `@NamedEntityGraph` / `EntityGraph` + JPQL `JOIN FETCH` | ✅ | Always-eager (graph ⊇ default) |
| Nested `@NamedSubgraph` (depth > 1) | ✅ | Recursive plan tree, per-level reactive batching; cycle fail-fast |
| `fetch = LAZY` | ✅ | Accepted (Nova batches rather than proxies) |

## Query languages

| Feature | Status | Notes |
|---|---|---|
| JPQL (`ReactiveEntityManager.createQuery`) | ⟳ | Hand-written lexer/parser/AST → SQL; injection-safe |
| JPQL `SELECT NEW` DTO, implicit joins, `LOCATE` / `CAST` / `FUNCTION` / `SIZE`, subqueries, bulk | ✅ | |
| JPQL / Criteria `TREAT()` / `TYPE()` polymorphism | ✅ | `SINGLE_TABLE`; discriminator-aware. Subquery/`JOINED` positions fail-fast |
| Criteria API (`jakarta.persistence.criteria`) | ⟳ | Joins (M2O/O2O/O2M/inverse), subqueries (`EXISTS`/`IN`/correlate) |
| Joins over a **composite-key** to-one target | ✅ | Multi-column `ON` (`a.c1=b.c1 AND a.c2=b.c2`) |
| `@NamedQuery` / `@NamedNativeQuery` | ✅ | Per-entity registry, duplicate-name fail-fast |
| `@SqlResultSetMapping` (`@EntityResult` / `@FieldResult` / `@ConstructorResult` / `@ColumnResult`) | ✅ | Native-read-then-coerce (dialect-independent) |
| `@StoredProcedureQuery` / `@NamedStoredProcedureQuery` | ⟳ | `IN` params + result sets. `OUT`/`INOUT`/`REF_CURSOR` fail-fast on r2dbc-h2 |

## EntityManager / session

| Feature | Status | Notes |
|---|---|---|
| `ReactiveEntityManager` (`persist` / `merge` / `remove` / `find` / `getReference` / `flush` / `clear` / `detach` / `refresh` / `contains`) | ⟳ | `Nova.entityManager(...)` |
| `LockModeType` (`PESSIMISTIC_WRITE`/`READ`, `OPTIMISTIC`, `FORCE_INCREMENT`) | ✅ | `find` / `lock` / `getLockMode` overloads |
| `FlushModeType` | ✅ | Propagated via Reactor `Context` |
| Transaction-bound persistence session (identity map + dirty checking + flush) | ✅ | Opt-in; collection diff-at-flush |
| 2nd-level cache (`nova-cache`, `@Cacheable` / `SharedCacheMode`) | ✅ | Read-through + query cache + post-commit type-region eviction |

## Spring

| Feature | Status | Notes |
|---|---|---|
| Spring Data-style `ReactiveCrudRepository<T, ID>` + `Pageable` / `Sort` | ✅ | `nova-spring-data`, opt-in `SpringDataReactiveCrudRepository` |
| `@Query` (JPQL) on repository methods | ✅ | `@EnableNovaRepositories`, `BeanFactoryAware` auto-wiring |

---

## Not yet supported (fail-fast)

These declare cleanly but are rejected with a message until implemented — Nova never mis-renders them:

- Composite-key to-one in a **projection / ordering-comparison / `IN` / `LIKE` / `BETWEEN`** position
  (`SELECT c.parent`, `WHERE c.parent < :x`, …). Multi-column **joins** and equality/`IS NULL` are supported.
- A composite-key to-one **leaf inside a nested `EntityGraph`** subgraph (flat depth-1 is supported).
- Stored-procedure `OUT` / `INOUT` / `REF_CURSOR` parameters (r2dbc-h2 driver limitation).
- `@MapKeyClass` naming an `@Embeddable` / entity key class.
- `@AssociationOverride` on an intermediate `@MappedSuperclass` (subclass-declared overrides work).
- In-place mutation of a *loaded* referenced entity's `@Id` (JPA-forbidden) is not change-tracked.

> Composite `@Id` components should be round-trip-stable types (integers, `String`, `UUID`, enums).
> Types whose stored form does not decode back byte-for-byte (`BigDecimal` scale drift, sub-second
> timestamp precision) are not recommended as key components.

For status and history of the parity work, see the module changelog / release notes (`v2.0.0`–`v2.7.0`).
