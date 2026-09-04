<!-- SPDX-License-Identifier: Apache-2.0 -->

# Transactions

`ReactiveEntityOperations.inTransaction(callback)` runs a callback inside a transaction boundary.

```java
// Always Propagation.REQUIRED, IsolationLevel.DEFAULT, readOnly=false
operations.inTransaction(tx ->
    tx.save(new Account(null, "a@example.com", true))
      .then(tx.save(new Account(null, "b@example.com", true)))
).subscribe();
```

`ReactiveEntityOperations` has only this single-argument form — there is no overload for
choosing `Propagation` / `IsolationLevel` / `readOnly`; every call runs with
`TransactionDefinition.DEFAULT`.

To control those, drop to the lower-level `ReactiveTransactionOperations` /
`ReactiveTransactionManager` (e.g. `R2dbcTransactionManager`) directly. Its
`inTransaction(TransactionDefinition, Function<TransactionContext, Mono<T>>)` opens the
transaction and threads the transactional connection through the Reactor `Context`; any
`ReactiveEntityOperations` call made **inside** that callback automatically joins it, because
`R2dbcSqlExecutor` reads the active connection from the same `Context` key.

```java
import io.nova.r2dbc.R2dbcTransactionManager;
import io.nova.tx.TransactionDefinition;
import io.nova.tx.IsolationLevel;
import io.nova.query.QuerySpec;

R2dbcTransactionManager txManager = new R2dbcTransactionManager(connectionFactory);

// REQUIRES_NEW — suspend the parent tx and run an isolated tx on a new connection
txManager.inTransaction(TransactionDefinition.requiresNew(), ctx ->
    operations.save(new Account(null, "c@example.com", true))
).subscribe();

// readOnly + SERIALIZABLE
TransactionDefinition def = TransactionDefinition.DEFAULT
        .with(IsolationLevel.SERIALIZABLE)
        .withReadOnly(true);
// The callback must return a Mono; collect the Flux read into one (or call a Mono-returning op).
txManager.inTransaction(def, ctx ->
    operations.findAll(Account.class, QuerySpec.empty()).collectList()
).subscribe();
```

In a Spring Boot application, the `novaTransactionManager` bean already is this
`R2dbcTransactionManager` — inject `ReactiveTransactionManager` alongside
`ReactiveEntityOperations` instead of constructing your own. See
[Spring](spring.md) for the bean reference.

Supported `Propagation` values: `REQUIRED`, `REQUIRES_NEW`, `NESTED` (SAVEPOINT), `MANDATORY`, `SUPPORTS`, `NOT_SUPPORTED`, `NEVER` — Spring semantics.

- An exception or `Mono.error` inside the callback **rolls back automatically**. `NESTED` rolls back only to the SAVEPOINT. Savepoint creation, rollback, release, outer completion, and cancellation cleanup are serialized on the physical connection; empty success also releases its savepoint.
- A caught error or absorbed cancellation from a participating `REQUIRED`, `MANDATORY`, or transactional `SUPPORTS` boundary marks its root (or current `NESTED` savepoint) rollback-only. Apparent success then rolls back and emits `UnexpectedRollbackException` with a stable message; pre-commit callbacks are skipped. An uncaught error is returned unchanged. A cancellation that reaches the owning subscription cannot subsequently emit this error, but still triggers rollback cleanup.
- `NESTED` rollback-only state is local to its savepoint: an outer callback may catch its `UnexpectedRollbackException` and commit. A failed rollback-to-savepoint marks the immediate parent rollback-only because containment was not proven; release-only failures do not. `REQUIRES_NEW` has an independent root and is unaffected by its suspended outer transaction.
- Transaction context propagates through the Reactor `Context`, so there is no thread leak (no `ThreadLocal`).
- A persistence session belongs to a **physical transaction**, not merely to a lexical callback. `REQUIRED`, `MANDATORY`, transactional `SUPPORTS`, and `NESTED` share the physical session; `REQUIRES_NEW` creates an independent connection, identity map, and pre-commit flush, then restores the outer session unchanged. Its error or cancellation rolls back and discards only the inner session.
- `NOT_SUPPORTED` suspends the physical transaction and installs no persistence session. Entity reads in that callback are stateless (repeated loads are distinct instances), unsaved mutations are ignored, and an explicit `save()` uses its normal autocommit behavior. The outer connection, identity map, and pending dirty state are restored when the callback exits.

---

## Persistence session (identity map + dirty checking)

Inside an `inTransaction(...)` callback, Nova activates a **transaction-bound persistence session** — a unit of work that rides the same Reactor `Context` as the transaction (no `ThreadLocal`). It gives you JPA-style identity and automatic change tracking; outside a transaction every operation stays stateless exactly as before.

```java
operations.inTransaction(tx ->
    tx.findById(Account.class, id)
      .flatMap(account -> {
          account.setEmail("new@example.com");   // just mutate the loaded entity
          return tx.save(account);               // no SQL issued here
      })
);
// On commit, the session flushes a single partial UPDATE of only the changed column.
```

- **Identity map** — loading the same primary key twice in one transaction returns the **same instance** (`first == second`).
- **Snapshot dirty checking** — each loaded entity is snapshotted (in storage form, so `@Convert`/`@Enumerated`/`@Embedded` columns compare correctly). At flush, only changed columns are written, as a partial `UPDATE`. No change → no SQL.
- **Flush timing** — automatically **before each `findById`/`findAll`** (read-your-writes within the transaction) and **once before commit**. An error rolls the transaction back, discarding pending changes.
- `save()` of a **new** entity still inserts immediately (to obtain the generated id); subsequent mutations are picked up by dirty checking. `save()` of an already-loaded entity issues no SQL — the change is flushed at commit.
- `@UpdatedAt`, `@PreUpdate`/`@PostUpdate`, and `@Version` optimistic locking apply to flush UPDATEs identically to an explicit partial update.
- For owning `@OneToOne(orphanRemoval = true)`, a managed replacement or nulling is flushed
  before commit in this order: owner FK update, same-owner shared-reference check, then normal
  deletion of the old target. Any callback, guard, or target-delete error fails the pre-commit
  work and rolls back both the owner update and target work; rollback does not rewind the Java
  object's already-mutated fields.
- `find(..., OPTIMISTIC_FORCE_INCREMENT)` and `find`/`lock` with `PESSIMISTIC_FORCE_INCREMENT` issue one version-increment UPDATE. For an exact managed instance, its `@Version` and `@UpdatedAt` snapshot is reconciled after that successful SQL, so commit does not repeat the increment or update callbacks.
- `getLockMode(entity)` reports the exact managed instance's last successfully applied `LockModeType`; an ordinary managed `find` reports `NONE`. Failed or cancelled lock work does not change the recorded mode. `refresh(entity, lockMode)` preserves both the entity state and its prior mode until the requested fresh read, lock, and any force-increment all succeed, then replaces state and records the requested mode. `remove` makes its exact managed instance report `NONE`; `detach`, `clear`, and physical-transaction completion discard all recorded modes. A same-id detached stand-in cannot observe, detach, refresh, or lock the canonical managed instance.
- `refresh` performs its fresh read without the persistence-session binding while retaining the surrounding Reactor transaction context. It replaces only scalar, embedded, and FK-column state on the supplied instance: relationship collections and fully hydrated association graphs are not replaced, and `cascade = REFRESH` is inert. Refetch with `ReactiveEntityOperations.findById(..., FetchGroup)` or `findById(..., EntityGraph)` when associations must be read again; those plans guarantee batching for named associations but are always-eager and do not selectively replace a graph in place. With `nova-cache`, that one read bypasses shared entity-cache lookup and population without evicting warm entity or query-cache entries; subsequent ordinary reads retain normal cache behavior.

**Current scope limits:** `NESTED` is a database savepoint on the same physical connection and therefore shares the outer persistence session, including exact-instance lock-mode records. A savepoint rollback does **not** rewind in-memory entity mutations, identity membership, dirty snapshots, or recorded lock modes; use `REQUIRES_NEW` when isolated persistence state is required. `REQUIRES_NEW` has an independent session and lock-mode records, and restores the outer session unchanged when it completes. `merge` of detached entities and a persistence session that outlives a single transaction are not supported. `update(entity, fields)` executes direct SQL, but after all its DML and `@PostUpdate` succeed it reconciles only the actual written storage columns on that exact managed instance. Its actual write set automatically includes `@UpdatedAt` and `@Version`; touching a secondary-table property full-writes and reconciles every updatable sibling in that secondary table, even when omitted from `fields`. Unrelated dirty fields remain pending, and a detached same-id instance cannot clean the canonical managed instance. The criteria `Updater` API remains session-bypassing. No snapshot advances on an error, cancellation, or post-callback failure; audit/pre-callback mutations and a successful version writeback are in-memory changes and are not rewound by a later error, cancellation, or transaction rollback. Reads other than entity-loading `findById`/`findAll` variants (for example `count` and scalar projections) are not auto-flushed. Ordinary, `FetchGroup`, and `EntityGraph` entity reads are session-managed.

Outside a transaction, an explicit `save(existingOwner)` with owning one-to-one orphan removal
loads the old reference, updates the owner, checks sharing, and removes the old target using
normal stateless/autocommit statements. Those statements are **not atomic**. Wrap the save in
`inTransaction(...)` whenever all-or-nothing replacement/nulling is required; Nova does not open
a hidden transaction for it.

---

## Read session (connection-scoped reads)

`inReadSession(...)` shares a **single pooled connection** across the reads in the callback —
**without** starting a transaction (no `BEGIN`/`COMMIT`, no persistence session). It removes the
per-operation connection acquire/release that each autocommit read otherwise pays.

```java
operations.inReadSession(ops ->
    ops.findAll(Order.class, recent)
       .collectList()
       .flatMap(orders -> Flux.fromIterable(orders)
           .concatMap(o -> ops.findById(Customer.class, o.getCustomerId()))
           .collectList()));
```

- **When it helps** — a logical unit that does **several reads** (a list plus related lookups).
  The connection is acquired once for the whole scope instead of once per read. A single read
  gains nothing (one acquire either way). Measured: 100 sequential `findById` dropped from
  ~654 µs to ~269 µs (**~2.4×**; ~3.85 µs/read saved) on H2 — confirming connection-acquire was
  the dominant per-op cost.
- **Reads are sequential** — an R2DBC connection is not concurrency-safe, so the scope assumes
  sequential reads (`concatMap`, not `flatMap`). Run genuinely concurrent reads outside the scope
  (each gets its own pooled connection) or in separate scopes.
- **No transaction** — statements stay autocommit on the shared connection, so there's **no
  scope-level atomicity**. Mixing writes is allowed but each autocommits independently; use
  `inTransaction(...)` when you need atomicity.
- **Nesting** — calling `inReadSession` inside an `inTransaction` (or another read session) reuses
  the already-bound connection; it never opens a second one. In the reverse direction, a bound read-session
  connection is not mistaken for an active transaction: `REQUIRED` and `NESTED` open a separately owned
  transaction, `MANDATORY` fails, and nontransactional `SUPPORTS` / `NEVER` reuse the read connection.
- Requires a connection-scope-aware wiring (the default `Nova.create` one). Other wirings fall
  back to per-operation acquire transparently.

---

## Pessimistic locking

Use `QuerySpec.forUpdate()` / `forShare()` to apply a pessimistic lock on the SELECT result rows. The lock clause is only meaningful **inside a transaction**, so use it within an `inTransaction(...)` callback.

`ReactiveEntityManager.find(..., PESSIMISTIC_*)` and
`ReactiveEntityManager.lock(entity, PESSIMISTIC_*)`, including
`PESSIMISTIC_FORCE_INCREMENT`, require an active transaction. They fail
reactively with `jakarta.persistence.TransactionRequiredException` before
issuing a `SELECT` or version-increment `UPDATE` when no transaction is active.
This validation applies only to the EntityManager `LockModeType` API; the raw
`QuerySpec` lock API remains unchanged.

Custom `ReactiveEntityManager` integrations must bind an active
`PhysicalTransactionScope` owner/scope in Reactor `Context` for a transaction.
An ambient persistence session alone does not satisfy this requirement.

```java
import io.nova.query.LockMode;

operations.inTransaction(tx ->
    tx.findAll(Account.class,
            QuerySpec.empty()
                .where(Criteria.eq("id", 42L))
                .forUpdate())                      // SELECT ... FOR UPDATE
      .next()
      .flatMap(account -> {
          account.setEmail("locked-" + account.getEmail());
          return tx.save(account);
      })
).subscribe();
```

Supported modes:

| `LockMode`   | SQL clause          | Semantics (standard SQL)                          |
|--------------|---------------------|---------------------------------------------------|
| `NONE`       | (none, default)     | No lock clause emitted                             |
| `FOR_UPDATE` | `FOR UPDATE`        | Blocks concurrent modifications and shared locks  |
| `FOR_SHARE`  | `FOR SHARE`         | Blocks exclusive locks; allows concurrent reads   |

`Dialect.lockClause(LockMode)` defaults to standard SQL `FOR UPDATE` / `FOR SHARE`, which works on PostgreSQL, MySQL 8.0+, and H2 unchanged. Dialects that need a variant override this method.

> **Caution**: the effective locking semantics depend on the transaction isolation level. Under `READ COMMITTED`, only the selected rows are locked; at `REPEATABLE READ` and above, phantom/gap behavior can differ — consult the dialect manual.

---

## Retry policy

For transient exceptions such as optimistic-lock conflicts, the reactive helper `ReactiveRetryTemplate` applies exponential-backoff retries. It is an immutable thin wrapper over Reactor's `Retry.backoff(...)`.

```java
import io.nova.retry.ReactiveRetryTemplate;

ReactiveRetryTemplate retry = ReactiveRetryTemplate.optimisticLockRetry();
// Policy: maxAttempts=3, initialBackoff=10ms, multiplier=2.0, maxBackoff=200ms,
//         retryable = OptimisticLockingFailureException::isInstance

retry.execute(
    operations.inTransaction(tx ->
        tx.findById(Account.class, 42L)
          .flatMap(account -> {
              account.setEmail("retried@nova.io");
              return tx.save(account);    // OptimisticLockingFailureException on @Version conflict
          })
    )
).subscribe();
```

Build a custom policy with the builder:

```java
ReactiveRetryTemplate custom = ReactiveRetryTemplate.builder()
        .maxAttempts(5)
        .initialBackoff(Duration.ofMillis(20))
        .backoffMultiplier(2.0)
        .maxBackoff(Duration.ofSeconds(1))
        .retryable(OptimisticLockingFailureException.class::isInstance)
        .build();
```

Operational caveats:

- **Jitter**: Reactor `Retry.backoff` applies ±50% jitter by default. The observed wait is chosen randomly in `[T*0.5, T*1.5]`, so it can transiently exceed `maxBackoff`.
- **Single-attempt short-circuit**: when `maxAttempts == 1`, all backoff settings are ignored and the call runs once exactly as if not wrapped.
- **Flux re-subscription**: `execute(Flux<T>)` uses `retryWhen`, so if the source emits some elements and then terminates with a retryable exception, **the already-emitted elements are re-emitted after retry**. For non-idempotent downstream consumers, supply your own dedup or idempotency key.
