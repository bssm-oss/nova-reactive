<!-- SPDX-License-Identifier: Apache-2.0 -->

# Transactions

`ReactiveTransactionOperations`는 콜백을 트랜잭션 경계 안에서 실행합니다. 기본 호출 외에 `Propagation` + `IsolationLevel` + `readOnly` 힌트를 담은 `TransactionDefinition` overload를 제공합니다.

```java
// 기본 — Propagation.REQUIRED, IsolationLevel.DEFAULT, readOnly=false
operations.inTransaction(tx ->
    tx.save(new Account(null, "a@example.com", true))
      .then(tx.save(new Account(null, "b@example.com", true)))
).subscribe();

// REQUIRES_NEW — 부모 tx를 suspend하고 새 connection으로 격리된 tx 실행
operations.inTransaction(TransactionDefinition.requiresNew(), tx -> ...);

// readOnly + SERIALIZABLE
TransactionDefinition def = TransactionDefinition.DEFAULT
        .with(IsolationLevel.SERIALIZABLE)
        .withReadOnly(true);
operations.inTransaction(def, tx -> ...);
```

지원 `Propagation`: `REQUIRED`, `REQUIRES_NEW`, `NESTED` (SAVEPOINT), `MANDATORY`, `SUPPORTS`, `NOT_SUPPORTED`, `NEVER` — Spring 의미 그대로.

- 콜백 내부에서 예외/`Mono.error`가 발생하면 **자동 롤백**됩니다. `NESTED`는 SAVEPOINT까지만 롤백.
- 트랜잭션 컨텍스트는 Reactor `Context`를 통해 전달되므로 스레드 누수에 안전 (`ThreadLocal` 사용 안 함).

---

## Pessimistic locking

`QuerySpec.forUpdate()` / `forShare()`로 SELECT 결과 행에 대한 pessimistic lock 강도를 지정할 수 있습니다. 락 절은 반드시 **트랜잭션 안**에서만 의미를 가지므로 `inTransaction(...)` 콜백 내부에서 사용하세요.

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

지원하는 모드:

| `LockMode`   | SQL 절              | 의미 (표준 SQL)                          |
|--------------|---------------------|-----------------------------------------|
| `NONE`       | (none, 기본값)      | 락 절 생성 안 함                          |
| `FOR_UPDATE` | `FOR UPDATE`        | 다른 트랜잭션의 동시 수정/공유 락 차단     |
| `FOR_SHARE`  | `FOR SHARE`         | 배타 락은 차단하되 공유 읽기는 허용         |

`Dialect.lockClause(LockMode)`의 기본 구현은 표준 SQL `FOR UPDATE` / `FOR SHARE`를 사용하며 PostgreSQL, MySQL 8.0+, H2 모두 그대로 동작합니다. dialect별 변형이 필요하면 이 메서드를 override 합니다.

> **주의**: 실제 잠금 의미는 트랜잭션 격리 수준에 따라 달라집니다. `READ COMMITTED`에서는 select된 행만, `REPEATABLE READ` 이상에서는 phantom/gap 동작이 달라질 수 있으니 dialect 매뉴얼을 함께 참고하세요.

---

## Retry policy

낙관적 락 충돌처럼 일시적(transient)인 예외에 대해 exponential backoff 재시도를 적용하는 reactive helper `ReactiveRetryTemplate`을 제공합니다. Reactor의 `Retry.backoff(...)`을 thin wrapping한 immutable 구조입니다.

```java
import io.nova.retry.ReactiveRetryTemplate;

ReactiveRetryTemplate retry = ReactiveRetryTemplate.optimisticLockRetry();
// 정책: maxAttempts=3, initialBackoff=10ms, multiplier=2.0, maxBackoff=200ms,
//      retryable = OptimisticLockingFailureException::isInstance

retry.execute(
    operations.inTransaction(tx ->
        tx.findById(Account.class, 42L)
          .flatMap(account -> {
              account.setEmail("retried@nova.io");
              return tx.save(account);    // @Version 충돌 시 OptimisticLockingFailureException
          })
    )
).subscribe();
```

사용자 정의 정책은 builder로 조립합니다.

```java
ReactiveRetryTemplate custom = ReactiveRetryTemplate.builder()
        .maxAttempts(5)
        .initialBackoff(Duration.ofMillis(20))
        .backoffMultiplier(2.0)
        .maxBackoff(Duration.ofSeconds(1))
        .retryable(OptimisticLockingFailureException.class::isInstance)
        .build();
```

운영 시 유의할 점:

- **Jitter**: Reactor `Retry.backoff`는 기본 ±50% jitter를 적용합니다. 실측 대기는 `[T*0.5, T*1.5]` 범위에서 무작위 결정되므로 `maxBackoff`를 일시적으로 초과할 수 있습니다.
- **단일 시도 short-circuit**: `maxAttempts == 1`이면 backoff 설정은 모두 무시되고 wrapper를 거치지 않은 것과 동일하게 한 번만 실행합니다.
- **Flux 재구독**: `execute(Flux<T>)`는 `retryWhen`을 사용하므로 source가 일부 값을 emit한 뒤 retryable 예외로 종료되면 **이미 발행된 값이 재시도 후 다시 emit**됩니다. 멱등하지 않은 downstream에는 별도 dedup/idempotency key가 필요합니다.
