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
        return Mono.usingWhen(
                Mono.defer(() -> transactionManager.begin(definition)),
                context -> Mono.defer(() -> callback.apply(context)),
                context -> transactionManager.commit(context)
                        .onErrorResume(error -> transactionManager.rollback(context).then(Mono.error(error))),
                (context, error) -> transactionManager.rollback(context),
                context -> transactionManager.rollback(context))
                .onErrorMap(SimpleReactiveTransactionOperations::unwrapCleanupFailure);
    }

    private static Throwable unwrapCleanupFailure(Throwable error) {
        if (error.getMessage() != null
                && error.getMessage().startsWith("Async resource cleanup failed")
                && error.getCause() != null) {
            return error.getCause();
        }
        return error;
    }
}
