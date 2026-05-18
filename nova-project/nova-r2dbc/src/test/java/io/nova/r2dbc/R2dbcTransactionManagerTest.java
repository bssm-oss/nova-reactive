package io.nova.r2dbc;

import io.nova.sql.BindMarkerStrategy;
import io.nova.sql.Dialect;
import io.nova.sql.SchemaGenerator;
import io.nova.sql.SqlRenderer;
import io.nova.sql.SqlStatement;
import io.nova.tx.IsolationLevel;
import io.nova.tx.Propagation;
import io.nova.tx.TransactionDefinition;
import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;

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
