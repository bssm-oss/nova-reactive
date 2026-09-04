package io.nova.core;

import io.nova.sql.SqlStatement;
import io.nova.tx.PhysicalTransactionScope;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.context.ContextView;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;

final class PhysicalTransactionWriteTrackingSqlExecutor implements SqlExecutor {
    private final SqlExecutor delegate;

    PhysicalTransactionWriteTrackingSqlExecutor(SqlExecutor delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
    }

    @Override
    public Mono<Long> execute(SqlStatement statement) {
        return Mono.deferContextual(context ->
                delegate.execute(statement).doOnSuccess(ignored -> markCompletedWrite(context)));
    }

    @Override
    public <T> Mono<T> queryOne(SqlStatement statement, Function<RowAccessor, T> mapper) {
        return delegate.queryOne(statement, mapper);
    }

    @Override
    public <T> Flux<T> queryMany(SqlStatement statement, Function<RowAccessor, T> mapper) {
        return delegate.queryMany(statement, mapper);
    }

    @Override
    public <T> Mono<T> executeAndReturnGeneratedKey(SqlStatement statement, String idColumn, Class<T> idType) {
        return Mono.deferContextual(context -> delegate.executeAndReturnGeneratedKey(statement, idColumn, idType)
                .doOnSuccess(ignored -> markCompletedWrite(context)));
    }

    @Override
    public Mono<Long> executeBatch(String sql, List<List<Object>> bindingsList) {
        return Mono.deferContextual(context ->
                delegate.executeBatch(sql, bindingsList).doOnSuccess(ignored -> markCompletedWrite(context)));
    }

    @Override
    public <T> Flux<T> executeBatchAndReturnGeneratedKeys(
            String sql, List<List<Object>> bindingsList, String idColumn, Class<T> idType) {
        return Flux.deferContextual(context ->
                delegate.executeBatchAndReturnGeneratedKeys(sql, bindingsList, idColumn, idType)
                        .doOnComplete(() -> markCompletedWrite(context)));
    }

    private static void markCompletedWrite(ContextView context) {
        if (context.hasKey(PhysicalTransactionScope.CONTEXT_KEY)) {
            context.<PhysicalTransactionScope>get(PhysicalTransactionScope.CONTEXT_KEY).markWriteCompleted();
        }
    }
}
