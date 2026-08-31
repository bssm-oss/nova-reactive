package io.nova.tx;

import java.util.Objects;
import java.util.function.Function;

import reactor.core.publisher.Mono;

/**
 * {@link ReactiveTransactionManager}의 트랜잭션 경계를 사용하는 헬퍼다.
 */
public final class SimpleReactiveTransactionOperations implements ReactiveTransactionOperations {
    private final ReactiveTransactionManager transactionManager;

    public SimpleReactiveTransactionOperations(ReactiveTransactionManager transactionManager) {
        this.transactionManager = Objects.requireNonNull(transactionManager, "transactionManager");
    }

    @Override
    public <T> Mono<T> inTransaction(TransactionDefinition definition,
                                     Function<TransactionContext, Mono<T>> callback) {
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(callback, "callback");
        return Mono.defer(() -> transactionManager.inTransaction(definition, callback));
    }
}
