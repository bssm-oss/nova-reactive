package io.nova.r2dbc;

import io.nova.tx.IsolationLevel;
import io.nova.tx.Propagation;
import io.nova.tx.ReactiveConnectionOperations;
import io.nova.tx.ReactiveTransactionManager;
import io.nova.tx.TransactionContext;
import io.nova.tx.TransactionDefinition;
import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.R2dbcException;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

public final class R2dbcTransactionManager implements ReactiveTransactionManager, ReactiveConnectionOperations {
    static final String CONNECTION_KEY = "io.nova.r2dbc.connection";

    private static final AtomicLong SAVEPOINT_COUNTER = new AtomicLong();

    private final ConnectionFactory connectionFactory;

    public R2dbcTransactionManager(ConnectionFactory connectionFactory) {
        this.connectionFactory = Objects.requireNonNull(connectionFactory, "connectionFactory");
    }

    @Override
    public Mono<TransactionContext> begin() {
        return begin(TransactionDefinition.DEFAULT);
    }

    @Override
    public Mono<TransactionContext> begin(TransactionDefinition definition) {
        Objects.requireNonNull(definition, "definition");
        return Mono.from(connectionFactory.create())
                .flatMap(conn -> applyPreTransactionSettings(conn, definition)
                        .then(Mono.from(conn.beginTransaction()))
                        .then(applyReadOnly(conn, definition))
                        .thenReturn((TransactionContext) new R2dbcTransactionContext(conn))
                        .onErrorResume(error -> Mono.from(conn.close())
                                .onErrorResume(closeError -> Mono.empty())
                                .then(Mono.error(error))));
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
    public <T> Mono<T> withConnection(Mono<T> work) {
        Objects.requireNonNull(work, "work");
        return Mono.deferContextual(ctxView -> {
            if (ctxView.hasKey(CONNECTION_KEY)) {
                // 이미 트랜잭션/바깥 read 스코프 안 → 기존 커넥션을 그대로 재사용한다(double-acquire 방지).
                return work;
            }
            // 스코프당 커넥션 1개를 풀에서 빌려 Context에 바인딩하고, 끝나면 반납한다(BEGIN/COMMIT 없음, autocommit).
            return Mono.usingWhen(
                    Mono.from(connectionFactory.create()),
                    conn -> work.contextWrite(Context.of(CONNECTION_KEY, conn)),
                    conn -> Mono.from(conn.close()));
        });
    }

    @Override
    public <T> Mono<T> inTransaction(TransactionDefinition definition,
                                     Function<TransactionContext, Mono<T>> callback) {
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(callback, "callback");
        return Mono.deferContextual(ctxView -> {
            Connection active = ctxView.hasKey(CONNECTION_KEY) ? ctxView.get(CONNECTION_KEY) : null;
            return runWithPropagation(definition, active, callback);
        });
    }

    private <T> Mono<T> runWithPropagation(TransactionDefinition definition,
                                           Connection active,
                                           Function<TransactionContext, Mono<T>> callback) {
        return switch (definition.propagation()) {
            case REQUIRED -> active == null
                    ? runInNewTransaction(definition, callback)
                    : joinActive(active, callback);
            case REQUIRES_NEW -> runInNewTransaction(definition, callback);
            case NESTED -> active == null
                    ? runInNewTransaction(definition, callback)
                    : runInSavepoint(active, callback);
            case MANDATORY -> active == null
                    ? Mono.error(new IllegalStateException(
                            "Propagation MANDATORY requires an active transaction, but none was found"))
                    : joinActive(active, callback);
            case SUPPORTS -> active == null
                    ? runWithoutTransaction(callback)
                    : joinActive(active, callback);
            case NOT_SUPPORTED -> runWithoutTransaction(callback);
            case NEVER -> active != null
                    ? Mono.error(new IllegalStateException(
                            "Propagation NEVER forbids an active transaction, but one was found"))
                    : runWithoutTransaction(callback);
        };
    }

    private <T> Mono<T> runInNewTransaction(TransactionDefinition definition,
                                            Function<TransactionContext, Mono<T>> callback) {
        return begin(definition).flatMap(ctx -> {
            Connection conn = ((R2dbcTransactionContext) ctx).connection();
            return callback.apply(ctx)
                    .contextWrite(Context.of(CONNECTION_KEY, conn))
                    .flatMap(result -> commit(ctx).thenReturn(result))
                    .onErrorResume(error -> rollback(ctx).onErrorResume(rb -> Mono.empty())
                            .then(Mono.error(error)));
        });
    }

    private <T> Mono<T> joinActive(Connection active, Function<TransactionContext, Mono<T>> callback) {
        TransactionContext ctx = new R2dbcTransactionContext(active);
        return callback.apply(ctx)
                .contextWrite(Context.of(CONNECTION_KEY, active));
    }

    private <T> Mono<T> runInSavepoint(Connection active, Function<TransactionContext, Mono<T>> callback) {
        String name = "nova_sp_" + SAVEPOINT_COUNTER.incrementAndGet();
        TransactionContext ctx = new R2dbcTransactionContext(active);
        return Mono.from(active.createSavepoint(name))
                .then(Mono.defer(() -> callback.apply(ctx))
                        .contextWrite(Context.of(CONNECTION_KEY, active))
                        .flatMap(result -> releaseSavepointIfSupported(active, name).thenReturn(result))
                        .onErrorResume(error -> Mono.from(active.rollbackTransactionToSavepoint(name))
                                .onErrorResume(rb -> Mono.empty())
                                .then(Mono.error(error))));
    }

    private static Mono<Void> releaseSavepointIfSupported(Connection conn, String name) {
        return Mono.defer(() -> {
            Publisher<Void> release;
            try {
                release = conn.releaseSavepoint(name);
            } catch (UnsupportedOperationException ignored) {
                return Mono.empty();
            }
            return Mono.from(release).onErrorResume(error -> {
                if (error instanceof UnsupportedOperationException) {
                    return Mono.empty();
                }
                return Mono.error(error);
            });
        });
    }

    /**
     * SUPPORTS/NOT_SUPPORTED/NEVER에서 활성 트랜잭션 없이 callback을 실행하는 경로다.
     * 전달되는 {@link TransactionContext}는 {@link R2dbcTransactionContext}이지만
     * connection은 {@code null}이고 {@link TransactionContext#hasActiveTransaction()}이
     * {@code false}이므로 callback이 resource에 접근하기 전에 반드시 확인해야 한다.
     * Reactor context의 {@link #CONNECTION_KEY}도 함께 비워 안쪽 executor가
     * 부모 트랜잭션 connection을 재사용하지 않고 새 auto-commit connection을 열게 한다.
     */
    private <T> Mono<T> runWithoutTransaction(Function<TransactionContext, Mono<T>> callback) {
        TransactionContext ctx = new R2dbcTransactionContext(null);
        return Mono.defer(() -> callback.apply(ctx))
                .contextWrite(c -> c.delete(CONNECTION_KEY));
    }

    private Mono<Void> applyPreTransactionSettings(Connection conn, TransactionDefinition definition) {
        Mono<Void> chain = Mono.defer(() -> Mono.from(conn.setAutoCommit(false)));
        if (definition.isolation() != IsolationLevel.DEFAULT) {
            io.r2dbc.spi.IsolationLevel target = toR2dbc(definition.isolation());
            chain = chain.then(Mono.defer(() -> Mono.from(conn.setTransactionIsolationLevel(target))));
        }
        return chain;
    }

    /**
     * 기본 {@code SET TRANSACTION READ ONLY} 시도. R2DBC SPI 1.0에는 read-only를 위한
     * 표준 hook이 없으므로 표준 SQL로 처리한다. 드라이버나 문법이 이 구문을 지원하지 않아
     * 발생하는 {@link R2dbcException}만 silently absorb 하고, 권한 오류·네트워크 단절 등
     * 일반 {@link RuntimeException}은 호출자에 그대로 전파한다.
     */
    private Mono<Void> applyReadOnly(Connection conn, TransactionDefinition definition) {
        if (!definition.readOnly()) {
            return Mono.empty();
        }
        return Mono.defer(() -> Mono.from(conn.createStatement("SET TRANSACTION READ ONLY").execute()))
                .flatMap(result -> Mono.from(result.getRowsUpdated()))
                .onErrorResume(R2dbcException.class, ignored -> Mono.empty())
                .then();
    }

    private static io.r2dbc.spi.IsolationLevel toR2dbc(IsolationLevel level) {
        return switch (level) {
            case READ_UNCOMMITTED -> io.r2dbc.spi.IsolationLevel.READ_UNCOMMITTED;
            case READ_COMMITTED -> io.r2dbc.spi.IsolationLevel.READ_COMMITTED;
            case REPEATABLE_READ -> io.r2dbc.spi.IsolationLevel.REPEATABLE_READ;
            case SERIALIZABLE -> io.r2dbc.spi.IsolationLevel.SERIALIZABLE;
            case DEFAULT -> throw new IllegalStateException("DEFAULT isolation must not be translated");
        };
    }
}
