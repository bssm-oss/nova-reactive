package io.nova.tx;

import reactor.core.publisher.Mono;

public interface ReactiveTransactionManager extends ReactiveTransactionOperations {
    Mono<TransactionContext> begin();

    Mono<Void> commit(TransactionContext context);

    Mono<Void> rollback(TransactionContext context);
}
