package io.nova.r2dbc;

import io.nova.tx.IsolationLevel;
import io.nova.tx.PhysicalTransactionScope;
import io.nova.tx.Propagation;
import io.nova.tx.ReactiveConnectionOperations;
import io.nova.tx.ReactiveTransactionManager;
import io.nova.tx.TransactionContext;
import io.nova.tx.TransactionDefinition;
import io.nova.tx.UnexpectedRollbackException;
import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.R2dbcException;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.util.context.Context;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;

public final class R2dbcTransactionManager implements ReactiveTransactionManager, ReactiveConnectionOperations {
    static final String CONNECTION_KEY = "io.nova.r2dbc.connection";
    private static final String ACTIVE_TRANSACTION_KEY = "io.nova.r2dbc.active-transaction";

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
        return Mono.usingWhen(
                Mono.from(connectionFactory.create()),
                conn -> applyPreTransactionSettings(conn, definition)
                        .then(Mono.from(conn.beginTransaction()))
                        .then(applyReadOnly(conn, definition))
                        .thenReturn((TransactionContext) new R2dbcTransactionContext(conn)),
                conn -> Mono.empty(),
                (conn, error) -> closeQuietly(conn),
                this::closeQuietly);
    }

    private Mono<Void> closeQuietly(Connection connection) {
        return Mono.defer(() -> Mono.from(connection.close()))
                .onErrorResume(closeError -> Mono.empty());
    }

    @Override
    public Mono<Void> commit(TransactionContext context) {
        Connection conn = ((R2dbcTransactionContext) context).connection();
        return closeAfter(conn, conn::commitTransaction);
    }

    @Override
    public Mono<Void> rollback(TransactionContext context) {
        Connection conn = ((R2dbcTransactionContext) context).connection();
        return closeAfter(conn, conn::rollbackTransaction);
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
            Connection connection = ctxView.hasKey(CONNECTION_KEY) ? ctxView.get(CONNECTION_KEY) : null;
            BoundaryOwner owner = ctxView.hasKey(ACTIVE_TRANSACTION_KEY)
                    ? ctxView.get(ACTIVE_TRANSACTION_KEY) : null;
            return runWithPropagation(definition, connection, owner, callback);
        });
    }

    private <T> Mono<T> runWithPropagation(TransactionDefinition definition,
                                           Connection connection,
                                           BoundaryOwner owner,
                                           Function<TransactionContext, Mono<T>> callback) {
        boolean activeTransaction = owner != null;
        return switch (definition.propagation()) {
            case REQUIRED -> !activeTransaction
                    ? runInNewTransaction(definition, callback)
                    : joinActive(connection, owner, callback);
            case REQUIRES_NEW -> runInNewTransaction(definition, callback);
            case NESTED -> !activeTransaction
                    ? runInNewTransaction(definition, callback)
                    : runInSavepoint(connection, owner, callback);
            case MANDATORY -> !activeTransaction
                    ? Mono.error(new IllegalStateException(
                            "Propagation MANDATORY requires an active transaction, but none was found"))
                    : joinActive(connection, owner, callback);
            case SUPPORTS -> !activeTransaction
                    ? runWithoutTransaction(callback, false)
                    : joinActive(connection, owner, callback);
            case NOT_SUPPORTED -> runWithoutTransaction(callback, activeTransaction);
            case NEVER -> activeTransaction
                    ? Mono.error(new IllegalStateException(
                            "Propagation NEVER forbids an active transaction, but one was found"))
                    : runWithoutTransaction(callback, false);
        };
    }

    private <T> Mono<T> runInNewTransaction(TransactionDefinition definition,
                                            Function<TransactionContext, Mono<T>> callback) {
        BoundaryOwner owner = new BoundaryOwner();
        PhysicalTransactionScope.Owner physicalOwner = PhysicalTransactionScope.newOwner();
        PhysicalTransactionScope scope = physicalOwner.scope();
        return Mono.usingWhen(
                begin(definition),
                ctx -> Mono.defer(() -> callback.apply(ctx))
                        .doOnCancel(owner::cancelQueued)
                        .flatMap(result -> prepareRootSuccess(owner, physicalOwner).thenReturn(result))
                        .switchIfEmpty(Mono.defer(() -> prepareRootSuccess(owner, physicalOwner)
                                .then(Mono.empty())))
                        .contextWrite(c -> c.put(CONNECTION_KEY, ((R2dbcTransactionContext) ctx).connection())
                                .put(ACTIVE_TRANSACTION_KEY, owner)
                                .put(PhysicalTransactionScope.CONTEXT_KEY, scope)),
                ctx -> owner.seal().then(commitAfterSuccess(ctx, physicalOwner.afterCommit()))
                        .onErrorMap(CleanupFailure::new),
                (ctx, error) -> owner.seal().then(physicalOwner.seal()).then(rollbackAfterError(ctx, error)),
                ctx -> owner.seal().then(physicalOwner.seal()).then(rollback(ctx)))
                .onErrorMap(R2dbcTransactionManager::unwrapCleanupFailure);
    }

    private Mono<Void> prepareRootSuccess(BoundaryOwner owner,
                                          PhysicalTransactionScope.Owner physicalOwner) {
        return owner.seal().then(Mono.defer(() -> owner.isRollbackOnly()
                ? Mono.error(new UnexpectedRollbackException())
                : physicalOwner.seal().then(physicalOwner.beforeCommit())));
    }

    private <T> Mono<T> joinActive(Connection active, BoundaryOwner owner,
                                   Function<TransactionContext, Mono<T>> callback) {
        TransactionContext ctx = new R2dbcTransactionContext(active);
        return Mono.defer(() -> callback.apply(ctx))
                .doOnError(ignored -> owner.markRollbackOnly())
                .doOnCancel(owner::markRollbackOnly)
                .contextWrite(c -> c.put(CONNECTION_KEY, active));
    }

    private <T> Mono<T> runInSavepoint(Connection active, BoundaryOwner parent,
                                       Function<TransactionContext, Mono<T>> callback) {
        return Mono.defer(() -> {
            SavepointScope scope = new SavepointScope(active, parent, parent.admit());
            TransactionContext ctx = new R2dbcTransactionContext(active);
            return Mono.usingWhen(
                    Mono.just(scope),
                    ignored -> scope.awaitTurn()
                            .then(scope.create())
                            .then(Mono.defer(() -> scope.active() ? callback.apply(ctx) : Mono.empty())
                                    .doOnCancel(scope.owner::cancelQueued)
                                    .contextWrite(c -> c.put(CONNECTION_KEY, active)
                                            .put(ACTIVE_TRANSACTION_KEY, scope.owner))),
                    SavepointScope::success,
                    SavepointScope::error,
                    SavepointScope::cancel)
                    .onErrorMap(R2dbcTransactionManager::unwrapCleanupFailure);
        });
    }

    private Mono<Void> rollbackAfterError(TransactionContext context, Throwable error) {
        return rollback(context)
                .onErrorMap(rollbackFailure -> {
                    rollbackFailure.addSuppressed(error);
                    return new CleanupFailure(rollbackFailure);
                });
    }

    private Mono<Void> commitAfterSuccess(TransactionContext context, Mono<Void> afterCommit) {
        Connection connection = ((R2dbcTransactionContext) context).connection();
        return closeAfter(connection, () -> Mono.defer(() -> Mono.from(connection.commitTransaction()))
                .onErrorResume(commitFailure -> Mono.defer(() -> Mono.from(connection.rollbackTransaction()))
                        .onErrorResume(rollbackFailure -> {
                            rollbackFailure.addSuppressed(commitFailure);
                            return Mono.error(rollbackFailure);
                        })
                        .then(Mono.error(commitFailure)))
                .then(afterCommit));
    }

    private Mono<Void> closeAfter(Connection connection, Supplier<org.reactivestreams.Publisher<Void>> operation) {
        return Mono.defer(() -> Mono.from(operation.get()))
                .onErrorResume(operationFailure -> close(connection)
                        .onErrorResume(closeFailure -> {
                            closeFailure.addSuppressed(operationFailure);
                            return Mono.error(closeFailure);
                        })
                        .then(Mono.error(operationFailure)))
                .then(close(connection));
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

    /**
     * Coordinates only transaction boundaries on a shared physical connection. Each
     * child owns a lease in its parent; descendants use a new owner so they do not
     * wait for their own ancestor's lease.
     */
    private static final class BoundaryOwner {
        private Mono<Void> tail = Mono.empty();
        private final java.util.List<Lease> leases = new java.util.ArrayList<>();
        private final AtomicBoolean rollbackOnly = new AtomicBoolean();
        private boolean sealed;

        void markRollbackOnly() {
            rollbackOnly.set(true);
        }

        boolean isRollbackOnly() {
            return rollbackOnly.get();
        }

        synchronized Lease admit() {
            if (sealed) {
                throw new IllegalStateException("Transaction boundary is already completing");
            }
            Lease lease = new Lease(tail);
            leases.add(lease);
            tail = lease.awaitCompletion();
            return lease;
        }

        synchronized Mono<Void> seal() {
            sealed = true;
            return tail;
        }

        synchronized void cancelQueued() {
            sealed = true;
            for (Lease lease : leases) {
                lease.cancelQueued();
            }
        }
    }

    private static final class Lease {
        private final Mono<Void> predecessor;
        private final Sinks.One<Void> completion = Sinks.one();
        private final AtomicBoolean completed = new AtomicBoolean();
        private volatile Runnable queuedCancellation = () -> {};

        private Lease(Mono<Void> predecessor) {
            this.predecessor = predecessor;
        }

        Mono<Void> awaitTurn() {
            return predecessor;
        }

        Mono<Void> awaitCompletion() {
            return predecessor.then(completion.asMono());
        }

        void complete() {
            if (completed.compareAndSet(false, true)) {
                completion.tryEmitEmpty();
            }
        }

        void onQueuedCancellation(Runnable cancellation) {
            queuedCancellation = cancellation;
        }

        void cancelQueued() {
            queuedCancellation.run();
        }
    }

    private static final class SavepointScope {
        private final Connection connection;
        private final String name = "nova_sp_" + SAVEPOINT_COUNTER.incrementAndGet();
        private final BoundaryOwner parent;
        private final Lease lease;
        private final BoundaryOwner owner = new BoundaryOwner();
        private final AtomicReference<SavepointState> state = new AtomicReference<>(SavepointState.QUEUED);
        private final AtomicBoolean created = new AtomicBoolean();
        private final Mono<Void> create;

        private SavepointScope(Connection connection, BoundaryOwner parent, Lease lease) {
            this.connection = connection;
            this.parent = parent;
            this.lease = lease;
            lease.onQueuedCancellation(this::cancelQueued);
            this.create = Mono.defer(() -> {
                        if (!state.compareAndSet(SavepointState.QUEUED, SavepointState.CREATING)) {
                            return Mono.empty();
                        }
                        return Mono.from(connection.createSavepoint(name));
                    })
                    .doOnSuccess(ignored -> {
                        created.set(true);
                        state.compareAndSet(SavepointState.CREATING, SavepointState.ACTIVE);
                    })
                    .doOnError(ignored -> state.compareAndSet(SavepointState.CREATING, SavepointState.CREATE_FAILED))
                    .cache();
        }

        Mono<Void> awaitTurn() {
            return lease.awaitTurn();
        }

        Mono<Void> create() {
            return create;
        }

        boolean active() {
            return state.get() == SavepointState.ACTIVE;
        }

        Mono<Void> success() {
            if (!selectTerminal(SavepointState.SUCCESS)) {
                return owner.seal().doFinally(ignored -> lease.complete());
            }
            return owner.seal()
                    .then(Mono.defer(() -> owner.isRollbackOnly()
                            ? rollbackAfterMarkedSuccess()
                            : releaseSavepointIfSupported(connection, name)))
                    .doFinally(ignored -> lease.complete())
                    .onErrorMap(CleanupFailure::new);
        }

        private Mono<Void> rollbackAfterMarkedSuccess() {
            UnexpectedRollbackException unexpectedRollback = new UnexpectedRollbackException();
            return rollbackAndRelease(unexpectedRollback).then(Mono.error(unexpectedRollback));
        }

        Mono<Void> error(Throwable callbackFailure) {
            if (!selectTerminal(SavepointState.ERROR)) {
                return owner.seal().doFinally(ignored -> lease.complete());
            }
            return owner.seal()
                    .then(state.get() == SavepointState.CREATE_FAILED
                            ? Mono.empty()
                            : rollbackAndRelease(callbackFailure))
                    .doFinally(ignored -> lease.complete())
                    .onErrorMap(CleanupFailure::new);
        }

        Mono<Void> cancel() {
            SavepointState selected;
            do {
                selected = state.get();
                if (selected == SavepointState.CREATE_FAILED) {
                    return owner.seal()
                            .then(create)
                            .doFinally(ignored -> lease.complete());
                }
                if (selected != SavepointState.QUEUED && selected != SavepointState.CREATING
                        && selected != SavepointState.ACTIVE) {
                    return Mono.empty();
                }
            } while (!state.compareAndSet(selected, SavepointState.CANCEL));
            if (selected == SavepointState.QUEUED) {
                return owner.seal().doFinally(ignored -> lease.complete());
            }
            return owner.seal()
                    .then(create)
                    .then(Mono.defer(() -> created.get() ? rollbackAndRelease(null) : Mono.empty()))
                    .doFinally(ignored -> lease.complete());
        }

        private void cancelQueued() {
            if (state.compareAndSet(SavepointState.QUEUED, SavepointState.CANCEL)) {
                owner.cancelQueued();
                lease.complete();
            }
        }

        private boolean selectTerminal(SavepointState terminal) {
            return state.compareAndSet(SavepointState.ACTIVE, terminal);
        }

        private Mono<Void> rollbackAndRelease(Throwable callbackFailure) {
            Mono<Void> rollback = Mono.defer(() -> Mono.from(connection.rollbackTransactionToSavepoint(name)));
            Mono<Void> release = releaseSavepointIfSupported(connection, name);
            return rollback.onErrorResume(rollbackFailure -> {
                        parent.markRollbackOnly();
                        return release
                            .onErrorMap(releaseFailure -> {
                                releaseFailure.addSuppressed(rollbackFailure);
                                return withSuppressed(releaseFailure, callbackFailure);
                            })
                            .then(Mono.error(withSuppressed(rollbackFailure, callbackFailure)));
                    })
                    .then(release.onErrorMap(releaseFailure ->
                            withSuppressed(releaseFailure, callbackFailure)));
        }

        private static Throwable withSuppressed(Throwable failure, Throwable suppressed) {
            if (suppressed != null) {
                failure.addSuppressed(suppressed);
            }
            return failure;
        }
    }

    private enum SavepointState {
        QUEUED, CREATING, ACTIVE, CREATE_FAILED, SUCCESS, ERROR, CANCEL
    }

    private Mono<Void> close(Connection connection) {
        return Mono.defer(() -> Mono.from(connection.close()));
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
     * 실제 트랜잭션을 suspend하는 경우에만 Reactor context의 {@link #CONNECTION_KEY}를 비워
     * 안쪽 executor가 부모 트랜잭션 connection을 재사용하지 않고 새 auto-commit connection을 열게 한다.
     * read session처럼 connection만 바인딩된 스코프에서는 connection을 보존한다.
     */
    private <T> Mono<T> runWithoutTransaction(Function<TransactionContext, Mono<T>> callback,
                                              boolean suspendConnection) {
        TransactionContext ctx = new R2dbcTransactionContext(null);
        Mono<T> work = Mono.defer(() -> callback.apply(ctx));
        return suspendConnection
                ? work.contextWrite(c -> c.delete(CONNECTION_KEY).delete(ACTIVE_TRANSACTION_KEY)
                        .put(PhysicalTransactionScope.CONTEXT_KEY, PhysicalTransactionScope.inactive()))
                : work;
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
