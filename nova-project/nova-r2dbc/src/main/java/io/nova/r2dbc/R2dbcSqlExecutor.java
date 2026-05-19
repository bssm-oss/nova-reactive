package io.nova.r2dbc;

import io.nova.core.RowAccessor;
import io.nova.core.SqlExecutionListener;
import io.nova.core.SqlExecutor;
import io.nova.sql.Dialect;
import io.nova.sql.SqlStatement;
import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.Statement;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

public final class R2dbcSqlExecutor implements SqlExecutor {
    private final ConnectionFactory connectionFactory;
    private final Dialect dialect;
    private final SqlExecutionListener listener;

    public R2dbcSqlExecutor(ConnectionFactory connectionFactory, Dialect dialect) {
        this(connectionFactory, dialect, SqlExecutionListener.NO_OP);
    }

    public R2dbcSqlExecutor(ConnectionFactory connectionFactory, Dialect dialect, SqlExecutionListener listener) {
        this.connectionFactory = Objects.requireNonNull(connectionFactory, "connectionFactory");
        this.dialect = Objects.requireNonNull(dialect, "dialect");
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    @Override
    public Mono<Long> execute(SqlStatement statement) {
        return Mono.defer(() -> {
            long startNanos = beforeExecution(statement);
            return withConnectionMono(conn -> Flux.from(bind(conn.createStatement(statement.sql()), statement.bindings()).execute())
                    .flatMap(result -> Mono.from(result.getRowsUpdated()))
                    .reduce(0L, Long::sum))
                    .doOnSuccess(rows -> afterExecution(statement, startNanos, rows == null ? 0L : rows))
                    .doOnError(error -> errored(statement, startNanos, error));
        });
    }

    @Override
    public Mono<Long> executeBatch(String sql, List<List<Object>> bindingsList) {
        if (bindingsList.isEmpty()) {
            return Mono.just(0L);
        }
        SqlStatement batchStatement = new SqlStatement(sql, List.of());
        return Mono.defer(() -> {
            long startNanos = beforeExecution(batchStatement);
            return withConnectionMono(conn -> {
                Statement stmt = conn.createStatement(sql);
                for (int i = 0; i < bindingsList.size(); i++) {
                    bind(stmt, bindingsList.get(i));
                    if (i < bindingsList.size() - 1) {
                        stmt.add();
                    }
                }
                return Flux.from(stmt.execute())
                        .flatMap(result -> Mono.from(result.getRowsUpdated()))
                        .reduce(0L, Long::sum);
            })
                    .doOnSuccess(rows -> afterExecution(batchStatement, startNanos, rows == null ? 0L : rows))
                    .doOnError(error -> errored(batchStatement, startNanos, error));
        });
    }

    @Override
    public <T> Mono<T> queryOne(SqlStatement statement, Function<RowAccessor, T> mapper) {
        return Mono.defer(() -> {
            long startNanos = beforeExecution(statement);
            AtomicLong rowCount = new AtomicLong();
            return withConnectionFlux(conn -> Flux.from(bind(conn.createStatement(statement.sql()), statement.bindings()).execute())
                    .flatMap(result -> result.map((row, meta) -> mapper.apply(new R2dbcRowAccessor(row)))))
                    .doOnNext(value -> rowCount.incrementAndGet())
                    .next()
                    .doOnSuccess(value -> afterExecution(statement, startNanos, rowCount.get()))
                    .doOnError(error -> errored(statement, startNanos, error));
        });
    }

    @Override
    public <T> Flux<T> queryMany(SqlStatement statement, Function<RowAccessor, T> mapper) {
        return Flux.defer(() -> {
            long startNanos = beforeExecution(statement);
            AtomicLong rowCount = new AtomicLong();
            return withConnectionFlux(conn -> Flux.from(bind(conn.createStatement(statement.sql()), statement.bindings()).execute())
                    .flatMap(result -> result.map((row, meta) -> mapper.apply(new R2dbcRowAccessor(row)))))
                    .doOnNext(value -> rowCount.incrementAndGet())
                    .doOnComplete(() -> afterExecution(statement, startNanos, rowCount.get()))
                    .doOnError(error -> errored(statement, startNanos, error));
        });
    }

    @Override
    public <T> Flux<T> executeBatchAndReturnGeneratedKeys(
            String sql, List<List<Object>> bindingsList, String idColumn, Class<T> idType) {
        if (bindingsList.isEmpty()) {
            return Flux.empty();
        }
        SqlStatement batchStatement = new SqlStatement(sql, List.of());
        return Flux.defer(() -> {
            long startNanos = beforeExecution(batchStatement);
            AtomicLong rowCount = new AtomicLong();
            return withConnectionFlux(conn -> {
                Statement stmt = conn.createStatement(sql);
                if (!dialect.usesReturningForGeneratedKeys()) {
                    stmt.returnGeneratedValues(idColumn);
                }
                for (int i = 0; i < bindingsList.size(); i++) {
                    bind(stmt, bindingsList.get(i));
                    if (i < bindingsList.size() - 1) {
                        stmt.add();
                    }
                }
                return Flux.from(stmt.execute())
                        .flatMap(result -> result.map((row, meta) -> row.get(idColumn, idType)));
            })
                    .doOnNext(value -> rowCount.incrementAndGet())
                    .doOnComplete(() -> afterExecution(batchStatement, startNanos, rowCount.get()))
                    .doOnError(error -> errored(batchStatement, startNanos, error));
        });
    }

    @Override
    public <T> Mono<T> executeAndReturnGeneratedKey(SqlStatement statement, String idColumn, Class<T> idType) {
        return Mono.defer(() -> {
            long startNanos = beforeExecution(statement);
            AtomicLong rowCount = new AtomicLong();
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
            })
                    .doOnNext(value -> rowCount.incrementAndGet())
                    .next()
                    .doOnSuccess(value -> afterExecution(statement, startNanos, rowCount.get()))
                    .doOnError(error -> errored(statement, startNanos, error));
        });
    }

    private long beforeExecution(SqlStatement statement) {
        listener.onBeforeExecution(statement);
        return System.nanoTime();
    }

    private void afterExecution(SqlStatement statement, long startNanos, long affectedRows) {
        listener.onAfterExecution(statement, elapsed(startNanos), affectedRows);
    }

    private void errored(SqlStatement statement, long startNanos, Throwable error) {
        listener.onError(statement, elapsed(startNanos), error);
    }

    private static Duration elapsed(long startNanos) {
        return Duration.ofNanos(System.nanoTime() - startNanos);
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

    private Statement bind(Statement statement, List<Object> bindings) {
        Class<?> nullType = dialect.nullBindClass();
        for (int i = 0; i < bindings.size(); i++) {
            Object value = bindings.get(i);
            if (value == null) {
                // driver별 null encoding 호환을 위해 dialect-provided fallback Class로 위임한다 —
                // 일부 driver(r2dbc-h2)는 Object.class null binding을 거부한다.
                statement.bindNull(i, nullType);
            } else {
                statement.bind(i, value);
            }
        }
        return statement;
    }
}
