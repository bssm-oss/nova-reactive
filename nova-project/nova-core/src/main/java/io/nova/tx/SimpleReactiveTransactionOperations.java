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
                this::commit,
                this::rollbackAfterError,
                this::rollback)
                .onErrorMap(SimpleReactiveTransactionOperations::unwrapCleanupFailure);
    }

    private Mono<Void> commit(TransactionContext context) {
        return Mono.defer(() -> transactionManager.commit(context))
                .onErrorMap(CleanupFailure::new)
                .onErrorResume(CleanupFailure.class, commitFailure -> rollback(context)
                        .onErrorMap(rollbackFailure -> {
                            rollbackFailure.addSuppressed(commitFailure.getCause());
                            return new CleanupFailure(rollbackFailure);
                        })
                        .then(Mono.error(commitFailure)));
    }

    private Mono<Void> rollbackAfterError(TransactionContext context, Throwable error) {
        return rollback(context)
                .onErrorMap(rollbackFailure -> {
                    rollbackFailure.addSuppressed(error);
                    return new CleanupFailure(rollbackFailure);
                });
    }

    private Mono<Void> rollback(TransactionContext context) {
        return Mono.defer(() -> transactionManager.rollback(context));
    }

    private static Throwable unwrapCleanupFailure(Throwable error) {
        if (error.getCause() instanceof CleanupFailure cleanupFailure) {
            return cleanupFailure.getCause();
        }
        return error;
    }

    private static final class CleanupFailure extends RuntimeException {
        private CleanupFailure(Throwable cause) {
            super(cause);
        }
    }
}
