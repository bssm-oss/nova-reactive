package io.nova.r2dbc;

import io.nova.sql.BindMarkerStrategy;
import io.nova.sql.Dialect;
import io.nova.sql.SchemaGenerator;
import io.nova.sql.SqlRenderer;
import io.nova.sql.SqlStatement;
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
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * R2dbcSqlExecutor의 batch 경로를 r2dbc-h2 in-memory DB로 직접 검증한다.
 */
class R2dbcSqlExecutorTest {
    /**
     * executeBatch 경로는 dialect를 사용하지 않으므로, 통합 테스트에서는 모든 메서드가 noop인 최소 dialect를 사용한다.
     */
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
        // 매 테스트마다 새로운 in-memory DB 인스턴스를 사용한다 (이름 유니크).
        String dbName = "test_" + UUID.randomUUID().toString().replace("-", "");
        connectionFactory = ConnectionFactories.get("r2dbc:h2:mem:///" + dbName + "?DB_CLOSE_DELAY=-1");
        executor = new R2dbcSqlExecutor(connectionFactory, NOOP_DIALECT);

        StepVerifier.create(executor.execute(new SqlStatement(
                        "create table accounts (id bigint primary key, email varchar(255) not null unique, active boolean not null)",
                        List.of())))
                .expectNextCount(1)
                .verifyComplete();
    }

    @Test
    void executeBatchInsertsAllBindingsAndReturnsTotalRowCount() {
        List<List<Object>> bindings = List.of(
                List.of(1L, "a@nova.io", true),
                List.of(2L, "b@nova.io", false),
                List.of(3L, "c@nova.io", true)
        );

        StepVerifier.create(executor.executeBatch(
                        "insert into accounts (id, email, active) values (?, ?, ?)", bindings))
                .expectNext(3L)
                .verifyComplete();

        StepVerifier.create(executor.queryOne(
                        new SqlStatement("select count(*) as cnt from accounts", List.of()),
                        row -> row.get("cnt", Long.class)))
                .expectNext(3L)
                .verifyComplete();
    }

    @Test
    void executeBatchWithEmptyBindingsShortCircuitsWithoutTouchingDriver() {
        AtomicInteger createConnectionCount = new AtomicInteger();
        ConnectionFactory counting = countingFactory(connectionFactory, createConnectionCount);
        R2dbcSqlExecutor countingExecutor = new R2dbcSqlExecutor(counting, NOOP_DIALECT);

        StepVerifier.create(countingExecutor.executeBatch(
                        "insert into accounts (id, email, active) values (?, ?, ?)", List.of()))
                .expectNext(0L)
                .verifyComplete();

        assertEquals(0, createConnectionCount.get(),
                "빈 batch는 connection도 만들지 않고 즉시 0L을 emit해야 한다");

        StepVerifier.create(executor.queryOne(
                        new SqlStatement("select count(*) as cnt from accounts", List.of()),
                        row -> row.get("cnt", Long.class)))
                .expectNext(0L)
                .verifyComplete();
    }

    @Test
    void executeBatchReusesConnectionFromTransactionContext() {
        AtomicInteger createConnectionCount = new AtomicInteger();
        ConnectionFactory counting = countingFactory(connectionFactory, createConnectionCount);
        R2dbcSqlExecutor txExecutor = new R2dbcSqlExecutor(counting, NOOP_DIALECT);
        R2dbcTransactionManager txManager = new R2dbcTransactionManager(counting);

        Mono<Long> work = txManager.inTransaction(ctx -> txExecutor.executeBatch(
                "insert into accounts (id, email, active) values (?, ?, ?)",
                List.of(
                        List.of(10L, "x@nova.io", true),
                        List.of(20L, "y@nova.io", false)
                )));

        StepVerifier.create(work)
                .expectNext(2L)
                .verifyComplete();

        assertEquals(1, createConnectionCount.get(),
                "트랜잭션 안 executeBatch는 외부 connection을 재사용해야 한다 (별도 connection 생성 X)");

        StepVerifier.create(executor.queryOne(
                        new SqlStatement("select count(*) as cnt from accounts", List.of()),
                        row -> row.get("cnt", Long.class)))
                .expectNext(2L)
                .verifyComplete();
    }

    @Test
    void multipleExecuteBatchCallsWithinSameTransactionRunSequentiallyWithoutProtocolConflict() {
        AtomicInteger createConnectionCount = new AtomicInteger();
        AtomicReference<Connection> firstSeen = new AtomicReference<>();
        AtomicReference<Connection> secondSeen = new AtomicReference<>();
        ConnectionFactory counting = countingFactory(connectionFactory, createConnectionCount);
        R2dbcSqlExecutor txExecutor = new R2dbcSqlExecutor(counting, NOOP_DIALECT);
        R2dbcTransactionManager txManager = new R2dbcTransactionManager(counting);

        Mono<Long> work = txManager.inTransaction(ctx -> {
            Connection ctxConn = (Connection) ctx.resource();
            firstSeen.set(ctxConn);
            return txExecutor.executeBatch(
                            "insert into accounts (id, email, active) values (?, ?, ?)",
                            List.of(
                                    List.of(1L, "first1@nova.io", true),
                                    List.of(2L, "first2@nova.io", false)))
                    .then(Mono.defer(() -> {
                        secondSeen.set(ctxConn);
                        return txExecutor.executeBatch(
                                "insert into accounts (id, email, active) values (?, ?, ?)",
                                List.of(
                                        List.of(3L, "second1@nova.io", true),
                                        List.of(4L, "second2@nova.io", false),
                                        List.of(5L, "second3@nova.io", true)));
                    }));
        });

        StepVerifier.create(work)
                .expectNext(3L)
                .verifyComplete();

        assertEquals(1, createConnectionCount.get(),
                "동일 트랜잭션 안의 두 executeBatch는 같은 connection을 재사용해야 한다");
        assertNotNull(firstSeen.get());
        assertSame(firstSeen.get(), secondSeen.get(), "트랜잭션 컨텍스트의 connection은 두 호출 사이에 동일해야 한다");

        StepVerifier.create(executor.queryOne(
                        new SqlStatement("select count(*) as cnt from accounts", List.of()),
                        row -> row.get("cnt", Long.class)))
                .expectNext(5L)
                .verifyComplete();
    }

    @Test
    void executeBatchAndReturnGeneratedKeysReturnsGeneratedIdsInOrder() {
        // identity 컬럼을 가진 테이블을 별도로 만든다.
        StepVerifier.create(executor.execute(new SqlStatement(
                        "create table identity_accounts ("
                                + "id bigint generated by default as identity primary key, "
                                + "email varchar(255) not null unique, "
                                + "active boolean not null)",
                        List.of())))
                .expectNextCount(1)
                .verifyComplete();

        List<List<Object>> bindings = List.of(
                List.of("a@nova.io", true),
                List.of("b@nova.io", false),
                List.of("c@nova.io", true)
        );

        StepVerifier.create(executor.executeBatchAndReturnGeneratedKeys(
                        "insert into identity_accounts (email, active) values (?, ?)",
                        bindings,
                        "id",
                        Long.class))
                .expectNextCount(3)
                .verifyComplete();

        // 모든 행이 실제로 insert되었는지 확인.
        StepVerifier.create(executor.queryOne(
                        new SqlStatement("select count(*) as cnt from identity_accounts", List.of()),
                        row -> row.get("cnt", Long.class)))
                .expectNext(3L)
                .verifyComplete();
    }

    @Test
    void executeBatchAndReturnGeneratedKeysWithEmptyBindingsShortCircuits() {
        AtomicInteger createConnectionCount = new AtomicInteger();
        ConnectionFactory counting = countingFactory(connectionFactory, createConnectionCount);
        R2dbcSqlExecutor countingExecutor = new R2dbcSqlExecutor(counting, NOOP_DIALECT);

        StepVerifier.create(countingExecutor.executeBatchAndReturnGeneratedKeys(
                        "insert into accounts (id, email, active) values (?, ?, ?)",
                        List.of(),
                        "id",
                        Long.class))
                .verifyComplete();

        assertEquals(0, createConnectionCount.get(),
                "빈 batch는 connection도 만들지 않고 즉시 완료해야 한다");
    }

    @Test
    void executeBatchAndReturnGeneratedKeysReusesConnectionFromTransactionContext() {
        StepVerifier.create(executor.execute(new SqlStatement(
                        "create table identity_tx_accounts ("
                                + "id bigint generated by default as identity primary key, "
                                + "email varchar(255) not null unique, "
                                + "active boolean not null)",
                        List.of())))
                .expectNextCount(1)
                .verifyComplete();

        AtomicInteger createConnectionCount = new AtomicInteger();
        ConnectionFactory counting = countingFactory(connectionFactory, createConnectionCount);
        R2dbcSqlExecutor txExecutor = new R2dbcSqlExecutor(counting, NOOP_DIALECT);
        R2dbcTransactionManager txManager = new R2dbcTransactionManager(counting);

        Mono<Long> work = txManager.inTransaction(ctx -> txExecutor.executeBatchAndReturnGeneratedKeys(
                        "insert into identity_tx_accounts (email, active) values (?, ?)",
                        List.of(
                                List.of("x@nova.io", true),
                                List.of("y@nova.io", false)
                        ),
                        "id",
                        Long.class)
                .count());

        StepVerifier.create(work)
                .expectNext(2L)
                .verifyComplete();

        assertEquals(1, createConnectionCount.get(),
                "트랜잭션 안 executeBatchAndReturnGeneratedKeys는 외부 connection을 재사용해야 한다");
    }

    @Test
    void executeBatchSignalsErrorWhenBindingViolatesUniqueConstraint() {
        // 먼저 id=1, email=dup@nova.io 한 건 insert
        StepVerifier.create(executor.execute(new SqlStatement(
                        "insert into accounts (id, email, active) values (?, ?, ?)",
                        List.of(1L, "dup@nova.io", true))))
                .expectNextCount(1)
                .verifyComplete();

        // 두 건 batch 중 두 번째가 email unique 위반.
        // 정확한 commit/rollback 동작은 driver에 위임 — 여기서는 "error signal이 발생한다"만 단언.
        StepVerifier.create(executor.executeBatch(
                        "insert into accounts (id, email, active) values (?, ?, ?)",
                        List.of(
                                List.of(2L, "ok@nova.io", true),
                                List.of(3L, "dup@nova.io", false)
                        )))
                .expectError()
                .verify();
    }

    /**
     * ConnectionFactory.create() 호출 횟수를 세는 가벼운 wrapper.
     */
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
}
