package io.nova.tx;

import reactor.core.publisher.Mono;

public interface ReactiveTransactionManager extends ReactiveTransactionOperations {
    /** {@link TransactionDefinition#DEFAULT} 정의로 새 트랜잭션을 시작한다. */
    Mono<TransactionContext> begin();

    /**
     * 주어진 {@link TransactionDefinition}으로 새 트랜잭션을 시작한다. 구현체가 propagation,
     * isolation, read-only를 지원하려면 이 메서드를 override해야 한다. 기본 구현은 정의를 무시하고
     * {@link #begin()}으로 위임한다.
     */
    default Mono<TransactionContext> begin(TransactionDefinition definition) {
        return begin();
    }

    Mono<Void> commit(TransactionContext context);

    Mono<Void> rollback(TransactionContext context);
}
