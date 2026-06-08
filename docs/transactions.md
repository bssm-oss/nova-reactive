<!-- SPDX-License-Identifier: Apache-2.0 -->

# Transactions

`ReactiveTransactionOperations` runs a callback inside a transaction boundary. Beyond the default call, it offers a `TransactionDefinition` overload carrying `Propagation` + `IsolationLevel` + `readOnly` hints.

```java
// Default — Propagation.REQUIRED, IsolationLevel.DEFAULT, readOnly=false
operations.inTransaction(tx ->
    tx.save(new Account(null, "a@example.com", true))
      .then(tx.save(new Account(null, "b@example.com", true)))
).subscribe();

// REQUIRES_NEW — suspend the parent tx and run an isolated tx on a new connection
operations.inTransaction(TransactionDefinition.requiresNew(), tx -> ...);

// readOnly + SERIALIZABLE
TransactionDefinition def = TransactionDefinition.DEFAULT
        .with(IsolationLevel.SERIALIZABLE)
        .withReadOnly(true);
operations.inTransaction(def, tx -> ...);
```

Supported `Propagation` values: `REQUIRED`, `REQUIRES_NEW`, `NESTED` (SAVEPOINT), `MANDATORY`, `SUPPORTS`, `NOT_SUPPORTED`, `NEVER` — Spring semantics.

- An exception or `Mono.error` inside the callback **rolls back automatically**. `NESTED` rolls back only to the SAVEPOINT.
- Transaction context propagates through the Reactor `Context`, so there is no thread leak (no `ThreadLocal`).

---

## Pessimistic locking

Use `QuerySpec.forUpdate()` / `forShare()` to apply a pessimistic lock on the SELECT result rows. The lock clause is only meaningful **inside a transaction**, so use it within an `inTransaction(...)` callback.

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
