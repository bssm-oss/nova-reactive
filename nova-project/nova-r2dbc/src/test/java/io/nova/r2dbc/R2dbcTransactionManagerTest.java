package io.nova.r2dbc;

import io.nova.sql.BindMarkerStrategy;
import io.nova.sql.Dialect;
import io.nova.sql.SchemaGenerator;
import io.nova.sql.SqlRenderer;
import io.nova.sql.SqlStatement;
import io.nova.tx.IsolationLevel;
import io.nova.tx.PhysicalTransactionScope;
import io.nova.tx.Propagation;
import io.nova.tx.TransactionDefinition;
import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R2dbcTransactionManager의 propagation, isolation, readOnly 동작을 H2 in-memory로 검증한다.
 */
class R2dbcTransactionManagerTest {
    private static final Dialect NOOP_DIALECT = new Dialect() {
        @Override public String name() { return "noop"; }
        @Override public String quote(String identifier) { return identifier; }
        @Override public BindMarkerStrategy bindMarkers() { return index -> "?"; }
        @Override public SqlRenderer sqlRenderer() { throw new UnsupportedOperationException(); }
        @Override public SchemaGenerator schemaGenerator() { throw new UnsupportedOperationException(); }
    };

    private ConnectionFactory connectionFactory;
    private R2dbcSqlExecutor executor;

    @BeforeEach
    void setUp() {
        String dbName = "tx_" + UUID.randomUUID().toString().replace("-", "");
        connectionFactory = ConnectionFactories.get("r2dbc:h2:mem:///" + dbName + "?DB_CLOSE_DELAY=-1");
        executor = new R2dbcSqlExecutor(connectionFactory, NOOP_DIALECT);

        StepVerifier.create(executor.execute(new SqlStatement(
                        "create table accounts (id bigint primary key, email varchar(255) not null)",
                        List.of())))
                .expectNextCount(1)
                .verifyComplete();
    }

    @Test
    void requiredJoinsActiveTransactionAndReusesConnection() {
        AtomicInteger created = new AtomicInteger();
        ConnectionFactory counting = countingFactory(connectionFactory, created);
        R2dbcTransactionManager txManager = new R2dbcTransactionManager(counting);
        R2dbcSqlExecutor txExecutor = new R2dbcSqlExecutor(counting, NOOP_DIALECT);

        Mono<Long> work = txManager.inTransaction(TransactionDefinition.DEFAULT, outer ->
                txExecutor.execute(new SqlStatement(
                                "insert into accounts (id, email) values (?, ?)",
                                List.of(1L, "outer@nova.io")))
                        .then(txManager.inTransaction(TransactionDefinition.DEFAULT, inner ->
                                txExecutor.execute(new SqlStatement(
                                        "insert into accounts (id, email) values (?, ?)",
                                        List.of(2L, "inner@nova.io"))))));

        StepVerifier.create(work).expectNext(1L).verifyComplete();

        assertEquals(1, created.get(),
                "REQUIRED 안 REQUIRED는 외부 connection을 재사용해야 한다");

        StepVerifier.create(executor.queryOne(
                        new SqlStatement("select count(*) as cnt from accounts", List.of()),
                        row -> row.get("cnt", Long.class)))
                .expectNext(2L)
                .verifyComplete();
    }

    @Test
    void requiresNewOpensSeparateConnection() {
        AtomicInteger created = new AtomicInteger();
        AtomicReference<Connection> outerConn = new AtomicReference<>();
        AtomicReference<Connection> innerConn = new AtomicReference<>();
        ConnectionFactory counting = countingFactory(connectionFactory, created);
        R2dbcTransactionManager txManager = new R2dbcTransactionManager(counting);

        Mono<Void> work = txManager.inTransaction(TransactionDefinition.DEFAULT, outer -> {
            outerConn.set(((R2dbcTransactionContext) outer).connection());
            return txManager.inTransaction(TransactionDefinition.requiresNew(), inner -> {
                innerConn.set(((R2dbcTransactionContext) inner).connection());
                return Mono.<Void>empty();
            });
        });

        StepVerifier.create(work).verifyComplete();

        assertEquals(2, created.get(),
                "REQUIRES_NEW는 부모와 별도의 connection을 새로 만들어야 한다");
        assertNotNull(outerConn.get());
        assertNotNull(innerConn.get());
        assertNotSame(outerConn.get(), innerConn.get(),
                "REQUIRES_NEW의 inner connection은 outer와 달라야 한다");
    }

    @Test
    void physicalScopeFollowsPropagationAndRestoresOuterScope() {
        R2dbcTransactionManager txManager = new R2dbcTransactionManager(connectionFactory);
        AtomicReference<PhysicalTransactionScope> outerScope = new AtomicReference<>();

        StepVerifier.create(txManager.inTransaction(TransactionDefinition.DEFAULT, outer ->
                        currentPhysicalScope().doOnNext(outerScope::set)
                                .then(txManager.inTransaction(TransactionDefinition.DEFAULT,
                                        required -> assertSamePhysicalScope(outerScope.get())))
                                .then(txManager.inTransaction(
                                        TransactionDefinition.DEFAULT.with(Propagation.MANDATORY),
                                        mandatory -> assertSamePhysicalScope(outerScope.get())))
                                .then(txManager.inTransaction(
                                        TransactionDefinition.DEFAULT.with(Propagation.SUPPORTS),
                                        supports -> assertSamePhysicalScope(outerScope.get())))
                                .then(txManager.inTransaction(
                                        TransactionDefinition.DEFAULT.with(Propagation.NESTED),
                                        nested -> assertSamePhysicalScope(outerScope.get())))
                                .then(txManager.inTransaction(TransactionDefinition.requiresNew(), requiresNew ->
                                        currentPhysicalScope().doOnNext(scope -> {
                                            assertNotSame(outerScope.get(), scope);
                                            assertTrue(scope.isActive());
                                        }).then()))
                                .then(currentPhysicalScope()
                                        .doOnNext(scope -> assertSame(outerScope.get(), scope)))
                                .then(txManager.inTransaction(
                                        TransactionDefinition.DEFAULT.with(Propagation.NOT_SUPPORTED),
                                        notSupported -> currentPhysicalScope().doOnNext(scope -> {
                                            assertSame(PhysicalTransactionScope.inactive(), scope);
                                            assertFalse(scope.isActive());
                                        }).then()))
                                .then(currentPhysicalScope()
                                        .doOnNext(scope -> assertSame(outerScope.get(), scope)))))
                .verifyComplete();

        StepVerifier.create(txManager.inTransaction(
                        TransactionDefinition.DEFAULT.with(Propagation.SUPPORTS),
                        supports -> Mono.deferContextual(context ->
                                Mono.just(context.hasKey(PhysicalTransactionScope.CONTEXT_KEY)))))
                .expectNext(false)
                .verifyComplete();
        StepVerifier.create(txManager.inTransaction(
                        TransactionDefinition.DEFAULT.with(Propagation.NEVER),
                        never -> Mono.deferContextual(context ->
                                Mono.just(context.hasKey(PhysicalTransactionScope.CONTEXT_KEY)))))
                .expectNext(false)
                .verifyComplete();
        StepVerifier.create(txManager.inTransaction(
                        TransactionDefinition.DEFAULT.with(Propagation.NOT_SUPPORTED),
                        notSupported -> Mono.deferContextual(context ->
                                Mono.just(context.hasKey(PhysicalTransactionScope.CONTEXT_KEY)))))
                .expectNext(false)
                .verifyComplete();
        StepVerifier.create(txManager.inTransaction(
                        TransactionDefinition.DEFAULT.with(Propagation.NESTED),
                        nested -> currentPhysicalScope().doOnNext(scope -> assertTrue(scope.isActive()))))
                .expectNextCount(1)
                .verifyComplete();
        StepVerifier.create(txManager.inTransaction(TransactionDefinition.DEFAULT, outer ->
                        txManager.inTransaction(
                                TransactionDefinition.DEFAULT.with(Propagation.NEVER),
                                never -> Mono.empty())))
                .expectError(IllegalStateException.class)
                .verify();
    }

    @Test
    void beforeCommitFailureRollsBackInsteadOfCommitting() {
        List<String> calls = new java.util.ArrayList<>();
        IllegalStateException flushFailure = new IllegalStateException("flush failed");
        Connection connection = (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "setAutoCommit", "beginTransaction" -> Mono.empty();
                    case "commitTransaction" -> {
                        calls.add("commit");
                        yield Mono.empty();
                    }
                    case "rollbackTransaction" -> {
                        calls.add("rollback");
                        yield Mono.empty();
                    }
                    case "close" -> {
                        calls.add("close");
                        yield Mono.empty();
                    }
                    default -> throw new AssertionError("Unexpected connection call: " + method.getName());
                });
        R2dbcTransactionManager txManager = transactionManager(connection);

        StepVerifier.create(txManager.inTransaction(TransactionDefinition.DEFAULT, context ->
                        Mono.deferContextual(reactorContext -> {
                            reactorContext.<PhysicalTransactionScope>get(PhysicalTransactionScope.CONTEXT_KEY)
                                    .beforeCommit(() -> Mono.error(flushFailure));
                            return Mono.just("work");
                        })))
                .expectErrorSatisfies(error -> assertSame(flushFailure, error))
                .verify();

        assertEquals(List.of("rollback", "close"), calls);
    }

    @Test
    void mandatoryWithoutActiveTransactionFailsWithIllegalState() {
        R2dbcTransactionManager txManager = new R2dbcTransactionManager(connectionFactory);

        Mono<String> work = txManager.inTransaction(
                TransactionDefinition.DEFAULT.with(Propagation.MANDATORY),
                ctx -> Mono.just("nope"));

        StepVerifier.create(work)
                .expectError(IllegalStateException.class)
                .verify();
    }

    @Test
    void mandatoryJoinsActiveTransaction() {
        AtomicInteger created = new AtomicInteger();
        ConnectionFactory counting = countingFactory(connectionFactory, created);
        R2dbcTransactionManager txManager = new R2dbcTransactionManager(counting);

        Mono<String> work = txManager.inTransaction(TransactionDefinition.DEFAULT, outer ->
                txManager.inTransaction(
                        TransactionDefinition.DEFAULT.with(Propagation.MANDATORY),
                        inner -> Mono.just("ok")));

        StepVerifier.create(work).expectNext("ok").verifyComplete();
        assertEquals(1, created.get(),
                "MANDATORY는 부모 connection을 재사용해야 한다");
    }

    @Test
    void neverWithActiveTransactionFailsWithIllegalState() {
        R2dbcTransactionManager txManager = new R2dbcTransactionManager(connectionFactory);

        Mono<String> work = txManager.inTransaction(TransactionDefinition.DEFAULT, outer ->
                txManager.inTransaction(
                        TransactionDefinition.DEFAULT.with(Propagation.NEVER),
                        inner -> Mono.just("nope")));

        StepVerifier.create(work)
                .expectError(IllegalStateException.class)
                .verify();
    }

    @Test
    void neverWithoutActiveTransactionRunsWithoutOpeningConnection() {
        AtomicInteger created = new AtomicInteger();
        ConnectionFactory counting = countingFactory(connectionFactory, created);
        R2dbcTransactionManager txManager = new R2dbcTransactionManager(counting);

        Mono<String> work = txManager.inTransaction(
                TransactionDefinition.DEFAULT.with(Propagation.NEVER),
                ctx -> Mono.just("ok"));

        StepVerifier.create(work).expectNext("ok").verifyComplete();
        assertEquals(0, created.get(),
                "NEVER는 활성 tx가 없을 때 새 connection을 만들지 않아야 한다");
    }

    @Test
    void supportsJoinsWhenTransactionActive() {
        AtomicInteger created = new AtomicInteger();
        ConnectionFactory counting = countingFactory(connectionFactory, created);
        R2dbcTransactionManager txManager = new R2dbcTransactionManager(counting);

        Mono<String> work = txManager.inTransaction(TransactionDefinition.DEFAULT, outer ->
                txManager.inTransaction(
                        TransactionDefinition.DEFAULT.with(Propagation.SUPPORTS),
                        inner -> Mono.just("ok")));

        StepVerifier.create(work).expectNext("ok").verifyComplete();
        assertEquals(1, created.get(),
                "SUPPORTS는 부모 connection을 재사용해야 한다");
    }

    @Test
    void supportsWithoutTransactionRunsCallbackWithoutOpeningConnection() {
        AtomicInteger created = new AtomicInteger();
        ConnectionFactory counting = countingFactory(connectionFactory, created);
        R2dbcTransactionManager txManager = new R2dbcTransactionManager(counting);

        Mono<String> work = txManager.inTransaction(
                TransactionDefinition.DEFAULT.with(Propagation.SUPPORTS),
                ctx -> Mono.just("ok"));

        StepVerifier.create(work).expectNext("ok").verifyComplete();
        assertEquals(0, created.get(),
                "SUPPORTS without active tx는 connection 생성 없이 callback만 실행해야 한다");
    }

    @Test
    void notSupportedSuspendsActiveTransactionForExecutorCalls() {
        AtomicInteger created = new AtomicInteger();
        ConnectionFactory counting = countingFactory(connectionFactory, created);
        R2dbcTransactionManager txManager = new R2dbcTransactionManager(counting);
        R2dbcSqlExecutor txExecutor = new R2dbcSqlExecutor(counting, NOOP_DIALECT);

        Mono<Long> work = txManager.inTransaction(TransactionDefinition.DEFAULT, outer ->
                txManager.inTransaction(
                        TransactionDefinition.DEFAULT.with(Propagation.NOT_SUPPORTED),
                        inner -> txExecutor.queryOne(
                                new SqlStatement("select count(*) as cnt from accounts", List.of()),
                                row -> row.get("cnt", Long.class))));

        StepVerifier.create(work).expectNext(0L).verifyComplete();

        // outer tx connection 1개 + inner NOT_SUPPORTED query가 사용한 auto-commit connection 1개 = 총 2개
        assertEquals(2, created.get(),
                "NOT_SUPPORTED 내부의 executor는 부모 connection을 쓰지 않고 새 auto-commit connection을 열어야 한다");
    }

    @Test
    void nestedSuccessReusesOuterConnectionAndCommitsAllWork() {
        AtomicInteger created = new AtomicInteger();
        ConnectionFactory counting = countingFactory(connectionFactory, created);
        R2dbcTransactionManager txManager = new R2dbcTransactionManager(counting);
        R2dbcSqlExecutor txExecutor = new R2dbcSqlExecutor(counting, NOOP_DIALECT);

        Mono<Long> work = txManager.inTransaction(TransactionDefinition.DEFAULT, outer ->
                txExecutor.execute(new SqlStatement(
                                "insert into accounts (id, email) values (?, ?)",
                                List.of(1L, "outer@nova.io")))
                        .then(txManager.inTransaction(
                                TransactionDefinition.DEFAULT.with(Propagation.NESTED),
                                inner -> txExecutor.execute(new SqlStatement(
                                        "insert into accounts (id, email) values (?, ?)",
                                        List.of(2L, "inner@nova.io"))))));

        StepVerifier.create(work).expectNext(1L).verifyComplete();

        assertEquals(1, created.get(),
                "NESTED는 부모 connection을 재사용하고 SAVEPOINT로만 격리해야 한다");

        StepVerifier.create(executor.queryOne(
                        new SqlStatement("select count(*) as cnt from accounts", List.of()),
                        row -> row.get("cnt", Long.class)))
                .expectNext(2L)
                .verifyComplete();
    }

    @Test
    void nestedRollbackErrorPropagatesAndDoesNotTearDownConnection() {
        AtomicInteger created = new AtomicInteger();
        ConnectionFactory counting = countingFactory(connectionFactory, created);
        R2dbcTransactionManager txManager = new R2dbcTransactionManager(counting);
        R2dbcSqlExecutor txExecutor = new R2dbcSqlExecutor(counting, NOOP_DIALECT);
        AtomicReference<Connection> seenConn = new AtomicReference<>();

        Mono<Void> work = txManager.inTransaction(TransactionDefinition.DEFAULT, outer -> {
            seenConn.set(((R2dbcTransactionContext) outer).connection());
            return txManager.inTransaction(
                            TransactionDefinition.DEFAULT.with(Propagation.NESTED),
                            inner -> txExecutor.execute(new SqlStatement(
                                            "insert into accounts (id, email) values (?, ?)",
                                            List.of(99L, "inner@nova.io")))
                                    .then(Mono.error(new RuntimeException("rollback inner"))))
                    .onErrorResume(e -> Mono.empty())
                    .then();
        });

        StepVerifier.create(work).verifyComplete();

        assertEquals(1, created.get(),
                "NESTED는 부모 connection을 재사용해야 한다");
        assertNotNull(seenConn.get());
    }

    @Test
    void recursiveNestedRollbackDoesNotDeadlockOrDiscardAncestorWork() {
        R2dbcTransactionManager txManager = new R2dbcTransactionManager(connectionFactory);
        R2dbcSqlExecutor txExecutor = new R2dbcSqlExecutor(connectionFactory, NOOP_DIALECT);

        Mono<Void> work = txManager.inTransaction(TransactionDefinition.DEFAULT, outer ->
                txExecutor.execute(new SqlStatement(
                                "insert into accounts (id, email) values (?, ?)",
                                List.of(10L, "outer@nova.io")))
                        .then(txManager.inTransaction(
                                        TransactionDefinition.DEFAULT.with(Propagation.NESTED), middle ->
                                                txExecutor.execute(new SqlStatement(
                                                                "insert into accounts (id, email) values (?, ?)",
                                                                List.of(11L, "middle@nova.io")))
                                                        .then(txManager.inTransaction(
                                                                TransactionDefinition.DEFAULT.with(Propagation.NESTED),
                                                                inner -> txExecutor.execute(new SqlStatement(
                                                                                "insert into accounts (id, email) values (?, ?)",
                                                                                List.of(12L, "inner@nova.io")))
                                                                        .then(Mono.error(
                                                                                new IllegalStateException("inner")))))
                                                        .onErrorResume(ignored -> Mono.empty())))
                        .then());

        StepVerifier.create(work).verifyComplete();
        StepVerifier.create(executor.queryOne(
                        new SqlStatement("select count(*) as cnt from accounts", List.of()),
                        row -> row.get("cnt", Long.class)))
                .expectNext(2L)
                .verifyComplete();
    }

    @Test
    void isolationLevelIsAppliedToConnection() {
        AtomicReference<io.r2dbc.spi.IsolationLevel> appliedIsolation = new AtomicReference<>();
        ConnectionFactory recording = recordingIsolationFactory(connectionFactory, appliedIsolation);
        R2dbcTransactionManager txManager = new R2dbcTransactionManager(recording);

        Mono<String> work = txManager.inTransaction(
                TransactionDefinition.DEFAULT.with(IsolationLevel.SERIALIZABLE),
                ctx -> Mono.just("ok"));

        StepVerifier.create(work).expectNext("ok").verifyComplete();
        assertEquals(io.r2dbc.spi.IsolationLevel.SERIALIZABLE, appliedIsolation.get(),
                "definition.isolation은 R2DBC Connection.setTransactionIsolationLevel로 전달되어야 한다");
    }

    @Test
    void defaultIsolationDoesNotCallSetTransactionIsolationLevel() {
        AtomicReference<io.r2dbc.spi.IsolationLevel> appliedIsolation = new AtomicReference<>();
        ConnectionFactory recording = recordingIsolationFactory(connectionFactory, appliedIsolation);
        R2dbcTransactionManager txManager = new R2dbcTransactionManager(recording);

        Mono<String> work = txManager.inTransaction(TransactionDefinition.DEFAULT, ctx -> Mono.just("ok"));

        StepVerifier.create(work).expectNext("ok").verifyComplete();
        // DEFAULT일 때는 driver 기본값을 건드리지 않아야 한다.
        org.junit.jupiter.api.Assertions.assertNull(appliedIsolation.get());
    }

    @Test
    void readOnlyDefinitionDoesNotFailExecution() {
        // H2가 SET TRANSACTION READ ONLY를 거부할 수 있으므로 read-only는 best-effort다.
        // 여기서는 "정의 자체가 실행을 깨뜨리지 않는다"만 확인한다.
        R2dbcTransactionManager txManager = new R2dbcTransactionManager(connectionFactory);

        Mono<String> work = txManager.inTransaction(TransactionDefinition.asReadOnly(), ctx -> Mono.just("ok"));

        StepVerifier.create(work).expectNext("ok").verifyComplete();
    }

    @Test
    void readOnlyAbsorbsR2dbcExceptionButPropagatesGenericRuntimeException() {
        // SET TRANSACTION READ ONLY 시도가 일반 RuntimeException(예: 권한 오류·드라이버 내부 오류)을
        // 던지면 흡수하지 않고 그대로 전파해야 한다. R2dbcException만 silently absorb 한다.
        ConnectionFactory faulty = readOnlyFaultyFactory(connectionFactory, new IllegalStateException("permission denied"));
        R2dbcTransactionManager txManager = new R2dbcTransactionManager(faulty);

        Mono<String> work = txManager.inTransaction(TransactionDefinition.asReadOnly(), ctx -> Mono.just("ok"));

        StepVerifier.create(work)
                .expectErrorMatches(error -> error instanceof IllegalStateException
                        && "permission denied".equals(error.getMessage()))
                .verify();
    }

    @Test
    void readOnlyAbsorbsR2dbcExceptionFromSetTransactionReadOnly() {
        // R2dbcException 계열은 driver/문법 미지원으로 보고 흡수해야 한다.
        ConnectionFactory faulty = readOnlyFaultyFactory(connectionFactory, new io.r2dbc.spi.R2dbcNonTransientResourceException("syntax not supported"));
        R2dbcTransactionManager txManager = new R2dbcTransactionManager(faulty);

        Mono<String> work = txManager.inTransaction(TransactionDefinition.asReadOnly(), ctx -> Mono.just("ok"));

        StepVerifier.create(work).expectNext("ok").verifyComplete();
    }

    @Test
    void runWithoutTransactionContextReportsNoActiveTransaction() {
        R2dbcTransactionManager txManager = new R2dbcTransactionManager(connectionFactory);

        Mono<Boolean> active = txManager.inTransaction(
                TransactionDefinition.DEFAULT.with(Propagation.NEVER),
                ctx -> Mono.just(ctx.hasActiveTransaction()));

        StepVerifier.create(active).expectNext(false).verifyComplete();
    }

    @Test
    void activeTransactionContextReportsActiveTrue() {
        R2dbcTransactionManager txManager = new R2dbcTransactionManager(connectionFactory);

        Mono<Boolean> active = txManager.inTransaction(
                TransactionDefinition.DEFAULT,
                ctx -> Mono.just(ctx.hasActiveTransaction()));

        StepVerifier.create(active).expectNext(true).verifyComplete();
    }

    @Test
    void boundReadConnectionStartsOwnedTransactionsForRequiredNestedAndRequiresNew() {
        AtomicInteger creates = new AtomicInteger();
        AtomicInteger begins = new AtomicInteger();
        AtomicInteger commits = new AtomicInteger();
        AtomicInteger closes = new AtomicInteger();
        Connection readConnection = connectionProxy(closes, null, null);
        ConnectionFactory factory = proxyFactory(creates, () -> connectionProxy(closes, begins, commits), readConnection);
        R2dbcTransactionManager txManager = new R2dbcTransactionManager(factory);

        StepVerifier.create(txManager.withConnection(
                        txManager.inTransaction(TransactionDefinition.DEFAULT, required -> {
                                    assertTrue(required.hasActiveTransaction());
                                    return Mono.empty();
                                })
                                .then(txManager.inTransaction(
                                        TransactionDefinition.DEFAULT.with(Propagation.NESTED), nested -> {
                                            assertTrue(nested.hasActiveTransaction());
                                            return Mono.empty();
                                        }))
                                .then(txManager.inTransaction(TransactionDefinition.requiresNew(), requiresNew -> {
                                    assertTrue(requiresNew.hasActiveTransaction());
                                    return Mono.empty();
                                }))))
                .verifyComplete();

        assertEquals(4, creates.get());
        assertEquals(3, begins.get());
        assertEquals(3, commits.get());
        assertEquals(4, closes.get());
    }

    @Test
    void boundReadConnectionIsReusedForNonTransactionalPropagations() {
        AtomicInteger creates = new AtomicInteger();
        AtomicInteger closes = new AtomicInteger();
        AtomicReference<Connection> seenConnection = new AtomicReference<>();
        Connection readConnection = connectionProxy(closes, null, null);
        R2dbcTransactionManager txManager = new R2dbcTransactionManager(
                proxyFactory(creates, () -> connectionProxy(closes, null, null), readConnection));

        StepVerifier.create(txManager.withConnection(
                        txManager.inTransaction(TransactionDefinition.DEFAULT.with(Propagation.SUPPORTS), supports ->
                                        readConnectionFromContext(seenConnection, supports.hasActiveTransaction()))
                                .then(txManager.inTransaction(
                                        TransactionDefinition.DEFAULT.with(Propagation.NOT_SUPPORTED), notSupported ->
                                                readConnectionFromContext(seenConnection,
                                                        notSupported.hasActiveTransaction())))
                                .then(txManager.inTransaction(
                                        TransactionDefinition.DEFAULT.with(Propagation.NEVER), never ->
                                                readConnectionFromContext(seenConnection,
                                                        never.hasActiveTransaction())))))
                .verifyComplete();
        assertSame(readConnection, seenConnection.get());
        assertEquals(1, creates.get());

        StepVerifier.create(txManager.withConnection(
                        txManager.inTransaction(
                                TransactionDefinition.DEFAULT.with(Propagation.MANDATORY), mandatory -> Mono.empty())))
                .expectError(IllegalStateException.class)
                .verify();
    }

    @Test
    void closesConnectionWhenBeginIsCancelledAfterAcquisition() {
        AtomicInteger acquisitions = new AtomicInteger();
        AtomicInteger beginTransactionCalls = new AtomicInteger();
        AtomicInteger closeCalls = new AtomicInteger();
        Connection connection = (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "setAutoCommit" -> Mono.empty();
                    case "beginTransaction" -> Mono.defer(() -> {
                        beginTransactionCalls.incrementAndGet();
                        return Mono.never();
                    });
                    case "close" -> {
                        closeCalls.incrementAndGet();
                        yield Mono.empty();
                    }
                    default -> throw new AssertionError("Unexpected connection call: " + method.getName());
                });
        ConnectionFactory factory = new ConnectionFactory() {
            @Override
            public Mono<? extends Connection> create() {
                return Mono.defer(() -> {
                    acquisitions.incrementAndGet();
                    return Mono.just(connection);
                });
            }

            @Override
            public ConnectionFactoryMetadata getMetadata() {
                return () -> "test";
            }
        };
        R2dbcTransactionManager txManager = new R2dbcTransactionManager(factory);

        StepVerifier.create(txManager.begin(), 1)
                .then(() -> {
                    assertEquals(1, acquisitions.get());
                    assertEquals(1, beginTransactionCalls.get());
                })
                .thenCancel()
                .verify();

        assertEquals(1, closeCalls.get());
    }

    @Test
    void rollsBackAndClosesWhenTransactionCallbackIsCancelled() {
        AtomicBoolean callbackEntered = new AtomicBoolean();
        AtomicInteger rollbackCalls = new AtomicInteger();
        AtomicInteger closeCalls = new AtomicInteger();
        Connection connection = transactionConnection(
                Mono.empty(), Mono.empty(), Mono.empty(), Mono.empty(),
                rollbackCalls, closeCalls);
        R2dbcTransactionManager txManager = transactionManager(connection);

        StepVerifier.create(txManager.inTransaction(
                        TransactionDefinition.DEFAULT,
                        context -> Mono.defer(() -> {
                            callbackEntered.set(true);
                            return Mono.never();
                        })),
                1)
                .then(() -> assertEquals(true, callbackEntered.get()))
                .thenCancel()
                .verify();

        assertEquals(1, rollbackCalls.get());
        assertEquals(1, closeCalls.get());
    }

    @Test
    void createSavepointFailureSkipsSavepointCleanupAndRollsBackOuterTransaction() {
        List<String> calls = new java.util.concurrent.CopyOnWriteArrayList<>();
        IllegalStateException createFailure = new IllegalStateException("create failed");
        Connection connection = (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "setAutoCommit", "beginTransaction" -> Mono.empty();
                    case "createSavepoint" -> {
                        calls.add("create");
                        yield Mono.error(createFailure);
                    }
                    case "rollbackTransactionToSavepoint" -> {
                        calls.add("rollback-savepoint");
                        yield Mono.empty();
                    }
                    case "releaseSavepoint" -> {
                        calls.add("release");
                        yield Mono.empty();
                    }
                    case "rollbackTransaction" -> {
                        calls.add("rollback");
                        yield Mono.empty();
                    }
                    case "close" -> {
                        calls.add("close");
                        yield Mono.empty();
                    }
                    default -> throw new AssertionError("Unexpected connection call: " + method.getName());
                });
        R2dbcTransactionManager txManager = transactionManager(connection);

        StepVerifier.create(txManager.inTransaction(TransactionDefinition.DEFAULT, outer ->
                        txManager.inTransaction(
                                TransactionDefinition.DEFAULT.with(Propagation.NESTED),
                                inner -> Mono.never())))
                .expectErrorSatisfies(error -> assertSame(createFailure, error))
                .verify();

        assertEquals(List.of("create", "rollback", "close"), calls);
    }

    @Test
    void rollsBackAndClosesWhenTransactionCallbackThrowsSynchronously() {
        AtomicInteger rollbackCalls = new AtomicInteger();
        AtomicInteger closeCalls = new AtomicInteger();
        IllegalStateException failure = new IllegalStateException("callback failed");
        Connection connection = transactionConnection(
                Mono.empty(), Mono.empty(), Mono.empty(), Mono.empty(),
                rollbackCalls, closeCalls);
        R2dbcTransactionManager txManager = transactionManager(connection);

        StepVerifier.create(txManager.inTransaction(TransactionDefinition.DEFAULT, context -> {
                    throw failure;
                }))
                .expectErrorSatisfies(error -> assertSame(failure, error))
                .verify();

        assertEquals(1, rollbackCalls.get());
        assertEquals(1, closeCalls.get());
    }

    @Test
    void surfacesRollbackFailureWithCallbackFailureSuppressed() {
        AtomicInteger rollbackCalls = new AtomicInteger();
        AtomicInteger closeCalls = new AtomicInteger();
        IllegalStateException callbackFailure = new IllegalStateException("callback failed");
        IllegalStateException rollbackFailure = new IllegalStateException("rollback failed");
        Connection connection = transactionConnection(
                Mono.empty(), Mono.empty(), Mono.error(rollbackFailure), Mono.empty(),
                rollbackCalls, closeCalls);
        R2dbcTransactionManager txManager = transactionManager(connection);

        StepVerifier.create(txManager.inTransaction(
                        TransactionDefinition.DEFAULT, context -> Mono.error(callbackFailure)))
                .expectErrorSatisfies(error -> {
                    assertSame(rollbackFailure, error);
                    assertEquals(List.of(callbackFailure), List.of(error.getSuppressed()));
                })
                .verify();

        assertEquals(1, rollbackCalls.get());
        assertEquals(1, closeCalls.get());
    }

    @Test
    void rollsBackAndClosesWhenCommitFailsDuringTransactionCompletion() {
        AtomicInteger rollbackCalls = new AtomicInteger();
        AtomicInteger closeCalls = new AtomicInteger();
        IllegalStateException commitFailure = new IllegalStateException("commit failed");
        Connection connection = transactionConnection(
                Mono.empty(), Mono.error(commitFailure), Mono.empty(), Mono.empty(),
                rollbackCalls, closeCalls);
        R2dbcTransactionManager txManager = transactionManager(connection);

        StepVerifier.create(txManager.inTransaction(TransactionDefinition.DEFAULT, context -> Mono.just("ok")))
                .expectErrorSatisfies(error -> assertSame(commitFailure, error))
                .verify();

        assertEquals(1, rollbackCalls.get());
        assertEquals(1, closeCalls.get());
    }

    @Test
    void surfacesRollbackFailureWhenCommitAndRollbackFailDuringCompletion() {
        AtomicInteger rollbackCalls = new AtomicInteger();
        AtomicInteger closeCalls = new AtomicInteger();
        IllegalStateException commitFailure = new IllegalStateException("commit failed");
        IllegalStateException rollbackFailure = new IllegalStateException("rollback failed");
        Connection connection = transactionConnection(
                Mono.empty(), Mono.error(commitFailure), Mono.error(rollbackFailure), Mono.empty(),
                rollbackCalls, closeCalls);
        R2dbcTransactionManager txManager = transactionManager(connection);

        StepVerifier.create(txManager.inTransaction(TransactionDefinition.DEFAULT, context -> Mono.just("ok")))
                .expectErrorSatisfies(error -> {
                    assertSame(rollbackFailure, error);
                    assertEquals(List.of(commitFailure), List.of(error.getSuppressed()));
                })
                .verify();

        assertEquals(1, rollbackCalls.get());
        assertEquals(1, closeCalls.get());
    }

    @Test
    void closesConnectionAfterCommitFailureAndPreservesCloseFailure() {
        AtomicInteger rollbackCalls = new AtomicInteger();
        AtomicInteger closeCalls = new AtomicInteger();
        IllegalStateException commitFailure = new IllegalStateException("commit failed");
        IllegalStateException closeFailure = new IllegalStateException("close failed");
        Connection connection = transactionConnection(
                Mono.empty(), Mono.error(commitFailure), Mono.empty(), Mono.error(closeFailure),
                rollbackCalls, closeCalls);
        R2dbcTransactionManager txManager = transactionManager(connection);

        StepVerifier.create(txManager.commit(new R2dbcTransactionContext(connection)))
                .expectErrorSatisfies(error -> {
                    assertSame(closeFailure, error);
                    assertEquals(List.of(commitFailure), List.of(error.getSuppressed()));
                })
                .verify();

        assertEquals(1, closeCalls.get());
    }

    @Test
    void closesConnectionAfterRollbackFailureAndPreservesCloseFailure() {
        AtomicInteger rollbackCalls = new AtomicInteger();
        AtomicInteger closeCalls = new AtomicInteger();
        IllegalStateException rollbackFailure = new IllegalStateException("rollback failed");
        IllegalStateException closeFailure = new IllegalStateException("close failed");
        Connection connection = transactionConnection(
                Mono.empty(), Mono.empty(), Mono.error(rollbackFailure), Mono.error(closeFailure),
                rollbackCalls, closeCalls);
        R2dbcTransactionManager txManager = transactionManager(connection);

        StepVerifier.create(txManager.rollback(new R2dbcTransactionContext(connection)))
                .expectErrorSatisfies(error -> {
                    assertSame(closeFailure, error);
                    assertEquals(List.of(rollbackFailure), List.of(error.getSuppressed()));
                })
                .verify();

        assertEquals(1, rollbackCalls.get());
        assertEquals(1, closeCalls.get());
    }

    @Test
    void nestedEmptySuccessReleasesSavepointBeforeOuterCommit() {
        List<String> calls = new java.util.ArrayList<>();
        Connection connection = (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "setAutoCommit", "beginTransaction" -> Mono.empty();
                    case "createSavepoint" -> {
                        calls.add("create");
                        yield Mono.empty();
                    }
                    case "releaseSavepoint" -> {
                        calls.add("release");
                        yield Mono.empty();
                    }
                    case "commitTransaction" -> {
                        calls.add("commit");
                        yield Mono.empty();
                    }
                    case "close" -> {
                        calls.add("close");
                        yield Mono.empty();
                    }
                    default -> throw new AssertionError("Unexpected connection call: " + method.getName());
                });
        R2dbcTransactionManager txManager = transactionManager(connection);

        StepVerifier.create(txManager.inTransaction(TransactionDefinition.DEFAULT, outer ->
                        txManager.inTransaction(
                                TransactionDefinition.DEFAULT.with(Propagation.NESTED),
                                inner -> Mono.empty())))
                .verifyComplete();

        assertEquals(List.of("create", "release", "commit", "close"), calls);
    }

    @Test
    void cancellingQueuedSiblingDoesNotCreateItsSavepoint() {
        AtomicInteger creates = new AtomicInteger();
        List<String> calls = new java.util.concurrent.CopyOnWriteArrayList<>();
        Sinks.Empty<Void> firstCallback = Sinks.empty();
        Connection connection = (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "setAutoCommit", "beginTransaction" -> Mono.empty();
                    case "createSavepoint" -> {
                        creates.incrementAndGet();
                        calls.add("create");
                        yield Mono.empty();
                    }
                    case "rollbackTransactionToSavepoint" -> {
                        calls.add("rollback-savepoint");
                        yield Mono.empty();
                    }
                    case "releaseSavepoint" -> {
                        calls.add("release");
                        yield Mono.empty();
                    }
                    case "rollbackTransaction" -> {
                        calls.add("rollback");
                        yield Mono.empty();
                    }
                    case "close" -> {
                        calls.add("close");
                        yield Mono.empty();
                    }
                    default -> throw new AssertionError("Unexpected connection call: " + method.getName());
                });
        R2dbcTransactionManager txManager = transactionManager(connection);

        StepVerifier.create(txManager.inTransaction(TransactionDefinition.DEFAULT, outer ->
                        Mono.deferContextual(context -> Mono.when(
                                txManager.inTransaction(
                                        TransactionDefinition.DEFAULT.with(Propagation.NESTED),
                                        inner -> firstCallback.asMono()),
                                txManager.inTransaction(
                                        TransactionDefinition.DEFAULT.with(Propagation.NESTED),
                                        inner -> Mono.never())))))
                .then(() -> assertEquals(1, creates.get()))
                .thenCancel()
                .verify();

        assertEquals(List.of("create", "rollback-savepoint", "release", "rollback", "close"), calls);
    }

    @Test
    void surfacesSavepointRollbackFailureWithNestedFailureSuppressed() {
        AtomicInteger closeCalls = new AtomicInteger();
        AtomicInteger releaseCalls = new AtomicInteger();
        IllegalStateException callbackFailure = new IllegalStateException("callback failed");
        IllegalStateException savepointFailure = new IllegalStateException("savepoint rollback failed");
        Connection connection = (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "setAutoCommit", "beginTransaction", "commitTransaction", "rollbackTransaction" -> Mono.empty();
                    case "createSavepoint" -> Mono.empty();
                    case "rollbackTransactionToSavepoint" -> Mono.error(savepointFailure);
                    case "releaseSavepoint" -> {
                        releaseCalls.incrementAndGet();
                        yield Mono.empty();
                    }
                    case "close" -> {
                        closeCalls.incrementAndGet();
                        yield Mono.empty();
                    }
                    default -> throw new AssertionError("Unexpected connection call: " + method.getName());
                });
        R2dbcTransactionManager txManager = transactionManager(connection);

        StepVerifier.create(txManager.inTransaction(TransactionDefinition.DEFAULT, outer ->
                        txManager.inTransaction(
                                TransactionDefinition.DEFAULT.with(Propagation.NESTED),
                                inner -> Mono.error(callbackFailure))))
                .expectErrorSatisfies(error -> {
                    assertSame(savepointFailure, error);
                    assertEquals(List.of(callbackFailure), List.of(error.getSuppressed()));
                })
                .verify();

        assertEquals(1, closeCalls.get());
        assertEquals(1, releaseCalls.get());
    }

    private static Mono<Void> assertSamePhysicalScope(PhysicalTransactionScope expected) {
        return currentPhysicalScope().doOnNext(scope -> {
            assertSame(expected, scope);
            assertTrue(scope.isActive());
        }).then();
    }

    private static Mono<PhysicalTransactionScope> currentPhysicalScope() {
        return Mono.deferContextual(context ->
                Mono.just(context.get(PhysicalTransactionScope.CONTEXT_KEY)));
    }

    private static Mono<Void> readConnectionFromContext(AtomicReference<Connection> sink, boolean activeTransaction) {
        assertFalse(activeTransaction);
        return Mono.deferContextual(context -> {
            sink.set(context.get(R2dbcTransactionManager.CONNECTION_KEY));
            return Mono.empty();
        });
    }

    private static ConnectionFactory proxyFactory(AtomicInteger creates,
                                                  Supplier<Connection> transactionConnection,
                                                  Connection readConnection) {
        return new ConnectionFactory() {
            @Override
            public Mono<? extends Connection> create() {
                return Mono.fromSupplier(() -> {
                    int acquisition = creates.incrementAndGet();
                    return acquisition == 1 ? readConnection : transactionConnection.get();
                });
            }

            @Override
            public ConnectionFactoryMetadata getMetadata() {
                return () -> "test";
            }
        };
    }

    private static Connection connectionProxy(AtomicInteger closes,
                                              AtomicInteger begins,
                                              AtomicInteger commits) {
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "setAutoCommit" -> Mono.empty();
                    case "beginTransaction" -> {
                        if (begins == null) {
                            throw new AssertionError("Read connection must not begin a transaction");
                        }
                        begins.incrementAndGet();
                        yield Mono.empty();
                    }
                    case "commitTransaction" -> {
                        if (commits == null) {
                            throw new AssertionError("Read connection must not commit a transaction");
                        }
                        commits.incrementAndGet();
                        yield Mono.empty();
                    }
                    case "close" -> {
                        closes.incrementAndGet();
                        yield Mono.empty();
                    }
                    default -> throw new AssertionError("Unexpected connection call: " + method.getName());
                });
    }

    private static R2dbcTransactionManager transactionManager(Connection connection) {
        return new R2dbcTransactionManager(new ConnectionFactory() {
            @Override
            public Mono<? extends Connection> create() {
                return Mono.just(connection);
            }

            @Override
            public ConnectionFactoryMetadata getMetadata() {
                return () -> "test";
            }
        });
    }

    private static Connection transactionConnection(Mono<Void> begin,
                                                    Mono<Void> commit,
                                                    Mono<Void> rollback,
                                                    Mono<Void> close,
                                                    AtomicInteger rollbackCalls,
                                                    AtomicInteger closeCalls) {
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "setAutoCommit" -> Mono.empty();
                    case "beginTransaction" -> begin;
                    case "commitTransaction" -> commit;
                    case "rollbackTransaction" -> {
                        rollbackCalls.incrementAndGet();
                        yield rollback;
                    }
                    case "close" -> {
                        closeCalls.incrementAndGet();
                        yield close;
                    }
                    default -> throw new AssertionError("Unexpected connection call: " + method.getName());
                });
    }

    /**
     * SET TRANSACTION READ ONLY 통계 호출에서 주어진 예외를 던지는 connection을 반환한다.
     * 다른 모든 method는 delegate로 위임한다.
     */
    private static ConnectionFactory readOnlyFaultyFactory(ConnectionFactory delegate, RuntimeException toThrow) {
        return new ConnectionFactory() {
            @Override
            public Mono<? extends Connection> create() {
                return Mono.from(delegate.create())
                        .map(conn -> (Connection) java.lang.reflect.Proxy.newProxyInstance(
                                Connection.class.getClassLoader(),
                                new Class<?>[]{Connection.class},
                                (proxy, method, args) -> {
                                    if ("createStatement".equals(method.getName())
                                            && args != null && args.length == 1
                                            && "SET TRANSACTION READ ONLY".equals(args[0])) {
                                        throw toThrow;
                                    }
                                    try {
                                        return method.invoke(conn, args);
                                    } catch (java.lang.reflect.InvocationTargetException e) {
                                        throw e.getCause();
                                    }
                                }));
            }

            @Override
            public ConnectionFactoryMetadata getMetadata() {
                return delegate.getMetadata();
            }
        };
    }

    private static ConnectionFactory countingFactory(ConnectionFactory delegate, AtomicInteger counter) {
        return new ConnectionFactory() {
            @Override
            public Mono<? extends Connection> create() {
                counter.incrementAndGet();
                return Mono.from(delegate.create());
            }

            @Override
            public ConnectionFactoryMetadata getMetadata() {
                return delegate.getMetadata();
            }
        };
    }

    /**
     * Connection.setTransactionIsolationLevel 호출을 기록하는 ConnectionFactory wrapper.
     */
    private static ConnectionFactory recordingIsolationFactory(ConnectionFactory delegate,
                                                               AtomicReference<io.r2dbc.spi.IsolationLevel> sink) {
        return new ConnectionFactory() {
            @Override
            public Mono<? extends Connection> create() {
                return Mono.from(delegate.create())
                        .map(conn -> (Connection) java.lang.reflect.Proxy.newProxyInstance(
                                Connection.class.getClassLoader(),
                                new Class<?>[]{Connection.class},
                                (proxy, method, args) -> {
                                    if ("setTransactionIsolationLevel".equals(method.getName()) && args != null && args.length == 1) {
                                        sink.set((io.r2dbc.spi.IsolationLevel) args[0]);
                                    }
                                    try {
                                        return method.invoke(conn, args);
                                    } catch (java.lang.reflect.InvocationTargetException e) {
                                        throw e.getCause();
                                    }
                                }));
            }

            @Override
            public ConnectionFactoryMetadata getMetadata() {
                return delegate.getMetadata();
            }
        };
    }
}
