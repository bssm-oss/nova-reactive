package io.nova.tx;

import reactor.core.publisher.Mono;

import java.util.Objects;
import java.util.function.Function;

/**
 * {@link ReactiveTransactionManager}에 begin/commit/rollback을 위임하는 트랜잭션 헬퍼다.
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
        return transactionManager.begin(definition)
                .flatMap(context -> callback.apply(context)
                        .flatMap(result -> transactionManager.commit(context).thenReturn(result))
                        .onErrorResume(error -> transactionManager.rollback(context).then(Mono.error(error))));
    }
}
