package io.nova.r2dbc;

import io.nova.tx.ReactiveTransactionManager;
import io.nova.tx.TransactionContext;
import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactory;
import reactor.core.publisher.Mono;

import java.util.function.Function;

public final class R2dbcTransactionManager implements ReactiveTransactionManager {
    static final String CONNECTION_KEY = "io.nova.r2dbc.connection";

    private final ConnectionFactory connectionFactory;

    public R2dbcTransactionManager(ConnectionFactory connectionFactory) {
        this.connectionFactory = connectionFactory;
    }

    @Override
    public Mono<TransactionContext> begin() {
        return Mono.from(connectionFactory.create())
                .flatMap(conn -> Mono.from(conn.setAutoCommit(false))
                        .then(Mono.from(conn.beginTransaction()))
                        .thenReturn(new R2dbcTransactionContext(conn)));
    }

    @Override
    public Mono<Void> commit(TransactionContext context) {
        Connection conn = ((R2dbcTransactionContext) context).connection();
        return Mono.from(conn.commitTransaction())
                .then(Mono.from(conn.close()));
    }

    @Override
    public Mono<Void> rollback(TransactionContext context) {
        Connection conn = ((R2dbcTransactionContext) context).connection();
        return Mono.from(conn.rollbackTransaction())
                .then(Mono.from(conn.close()));
    }

    @Override
    public <T> Mono<T> inTransaction(Function<TransactionContext, Mono<T>> callback) {
        return begin().flatMap(ctx ->
                callback.apply(ctx)
                        .contextWrite(reactor.util.context.Context.of(CONNECTION_KEY, ctx.resource()))
                        .flatMap(result -> commit(ctx).thenReturn(result))
                        .onErrorResume(error -> rollback(ctx).then(Mono.error(error))));
    }
}
