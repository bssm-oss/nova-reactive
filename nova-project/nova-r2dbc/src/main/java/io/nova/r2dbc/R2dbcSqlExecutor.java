package io.nova.r2dbc;

import io.nova.core.RowAccessor;
import io.nova.core.SqlExecutor;
import io.nova.sql.Dialect;
import io.nova.sql.SqlStatement;
import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.Statement;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.function.Function;

public final class R2dbcSqlExecutor implements SqlExecutor {
    private final ConnectionFactory connectionFactory;
    private final Dialect dialect;

    public R2dbcSqlExecutor(ConnectionFactory connectionFactory, Dialect dialect) {
        this.connectionFactory = connectionFactory;
        this.dialect = dialect;
    }

    @Override
    public Mono<Long> execute(SqlStatement statement) {
        return withConnectionMono(conn -> Flux.from(bind(conn.createStatement(statement.sql()), statement.bindings()).execute())
                .flatMap(result -> Mono.from(result.getRowsUpdated()))
                .reduce(0L, Long::sum));
    }

    @Override
    public <T> Mono<T> queryOne(SqlStatement statement, Function<RowAccessor, T> mapper) {
        return withConnectionFlux(conn -> Flux.from(bind(conn.createStatement(statement.sql()), statement.bindings()).execute())
                .flatMap(result -> result.map((row, meta) -> mapper.apply(new R2dbcRowAccessor(row)))))
                .next();
    }

    @Override
    public <T> Flux<T> queryMany(SqlStatement statement, Function<RowAccessor, T> mapper) {
        return withConnectionFlux(conn -> Flux.from(bind(conn.createStatement(statement.sql()), statement.bindings()).execute())
                .flatMap(result -> result.map((row, meta) -> mapper.apply(new R2dbcRowAccessor(row)))));
    }

    @Override
    public <T> Mono<T> executeAndReturnGeneratedKey(SqlStatement statement, String idColumn, Class<T> idType) {
        return withConnectionFlux(conn -> {
            Statement bound;
            if (dialect.usesReturningForGeneratedKeys()) {
                bound = bind(conn.createStatement(statement.sql()), statement.bindings());
            } else {
                bound = bind(conn.createStatement(statement.sql()), statement.bindings())
                        .returnGeneratedValues(idColumn);
            }
            return Flux.from(bound.execute())
                    .flatMap(result -> result.map((row, meta) -> row.get(idColumn, idType)));
        }).next();
    }

    private <T> Mono<T> withConnectionMono(Function<Connection, Mono<T>> work) {
        return Mono.deferContextual(ctx -> {
            if (ctx.hasKey(R2dbcTransactionManager.CONNECTION_KEY)) {
                Connection conn = ctx.get(R2dbcTransactionManager.CONNECTION_KEY);
                return work.apply(conn);
            }
            return Mono.usingWhen(
                    Mono.from(connectionFactory.create()),
                    work,
                    conn -> Mono.from(conn.close()));
        });
    }

    private <T> Flux<T> withConnectionFlux(Function<Connection, Flux<T>> work) {
        return Flux.deferContextual(ctx -> {
            if (ctx.hasKey(R2dbcTransactionManager.CONNECTION_KEY)) {
                Connection conn = ctx.get(R2dbcTransactionManager.CONNECTION_KEY);
                return work.apply(conn);
            }
            return Flux.usingWhen(
                    Mono.from(connectionFactory.create()),
                    work,
                    conn -> Mono.from(conn.close()));
        });
    }

    private static Statement bind(Statement statement, List<Object> bindings) {
        for (int i = 0; i < bindings.size(); i++) {
            Object value = bindings.get(i);
            if (value == null) {
                statement.bindNull(i, Object.class);
            } else {
                statement.bind(i, value);
            }
        }
        return statement;
    }
}
