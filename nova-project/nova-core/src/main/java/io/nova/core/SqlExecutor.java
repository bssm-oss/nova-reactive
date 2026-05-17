package io.nova.core;

import io.nova.sql.SqlStatement;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.function.Function;

public interface SqlExecutor {
    Mono<Long> execute(SqlStatement statement);

    <T> Mono<T> queryOne(SqlStatement statement, Function<RowAccessor, T> mapper);

    <T> Flux<T> queryMany(SqlStatement statement, Function<RowAccessor, T> mapper);
}
