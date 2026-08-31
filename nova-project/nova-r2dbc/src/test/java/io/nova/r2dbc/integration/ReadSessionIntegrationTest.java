package io.nova.r2dbc.integration;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import io.nova.core.EntityStateDetector;
import io.nova.core.ReactiveEntityOperations;
import io.nova.core.SimpleReactiveEntityOperations;
import io.nova.dialect.h2.H2Dialect;
import io.nova.metadata.DefaultNamingStrategy;
import io.nova.metadata.EntityMetadataFactory;
import io.nova.query.QuerySpec;
import io.nova.r2dbc.R2dbcSqlExecutor;
import io.nova.r2dbc.R2dbcTransactionManager;
import io.nova.schema.SimpleSchemaInitializer;
import io.nova.sql.Dialect;
import io.nova.sql.SqlStatement;
import io.nova.tx.Propagation;
import io.nova.tx.SimpleReactiveTransactionOperations;
import io.nova.tx.TransactionDefinition;
import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code inReadSession}이 스코프 동안 커넥션 하나만 빌려 다중 read가 공유하는지(= per-op acquire 제거)와
 * 정확성을 검증한다. 커넥션 acquire 횟수는 {@link ConnectionFactory#create()} 호출을 세는 래퍼로 직접 센다.
 */
class ReadSessionIntegrationTest {

    private CountingConnectionFactory counting;
    private ReactiveEntityOperations operations;
    private R2dbcSqlExecutor executor;
    private R2dbcTransactionManager transactionManager;
    private SimpleReactiveTransactionOperations transactionOperations;

    @BeforeEach
    void setUp() {
        String url = "r2dbc:h2:mem:///readsess_" + UUID.randomUUID().toString().replace("-", "") + "?DB_CLOSE_DELAY=-1";
        this.counting = new CountingConnectionFactory(ConnectionFactories.get(url));
        Dialect dialect = new H2Dialect();
        EntityMetadataFactory metadataFactory = new EntityMetadataFactory(new DefaultNamingStrategy());
        this.executor = new R2dbcSqlExecutor(counting, dialect);
        this.transactionManager = new R2dbcTransactionManager(counting);
        this.transactionOperations = new SimpleReactiveTransactionOperations(transactionManager);
        this.operations = new SimpleReactiveEntityOperations(
                metadataFactory, dialect, executor, new EntityStateDetector(), transactionManager);
        new SimpleSchemaInitializer(operations, metadataFactory, dialect).create(Person.class).block();
        // seed 3 rows
        for (int i = 0; i < 3; i++) {
            operations.save(new Person("p" + i)).block();
        }
    }

    @Test
    void readSessionSharesOneConnectionAcrossReads() {
        counting.reset();
        StepVerifier.create(operations.inReadSession(ops ->
                        Flux.range(1, 3)
                                .concatMap(id -> ops.findById(Person.class, (long) id))
                                .collectList()))
                .assertNext(found -> assertEquals(3, found.size()))
                .verifyComplete();
        assertEquals(1, counting.creates(), "read 세션 안 3개의 read는 커넥션 1개를 공유해야 한다");
    }

    @Test
    void withoutReadSessionEachReadAcquiresAConnection() {
        counting.reset();
        StepVerifier.create(Flux.range(1, 3)
                        .concatMap(id -> operations.findById(Person.class, (long) id))
                        .collectList())
                .assertNext(found -> assertEquals(3, found.size()))
                .verifyComplete();
        assertEquals(3, counting.creates(), "read 세션 밖에서는 read마다 커넥션을 새로 빌린다");
    }

    @Test
    void readSessionReturnsCorrectData() {
        StepVerifier.create(operations.inReadSession(ops ->
                        ops.findAll(Person.class, QuerySpec.empty()).collectList()))
                .assertNext(all -> assertEquals(3, all.size()))
                .verifyComplete();
    }

    @Test
    void nestedInTransactionJoinsWithoutNewConnection() {
        counting.reset();
        // inTransaction이 커넥션 1개를 따고, 그 안의 inReadSession은 그것을 join한다(새로 따지 않음).
        StepVerifier.create(operations.inTransaction(tx ->
                        tx.inReadSession(ops ->
                                ops.findById(Person.class, 1L)
                                        .then(ops.findById(Person.class, 2L)))))
                .expectNextCount(1)
                .verifyComplete();
        assertEquals(1, counting.creates(), "중첩 read 세션은 트랜잭션 커넥션을 재사용해야 한다");
    }

    @Test
    void helperTransactionCommitsRollsBackAndPreservesPropagationSemantics() {
        StepVerifier.create(executor.execute(new SqlStatement(
                        "create table propagation_rows (id bigint primary key)", List.of())))
                .expectNextCount(1)
                .verifyComplete();

        counting.reset();
        StepVerifier.create(transactionManager.withConnection(
                        transactionOperations.inTransaction(TransactionDefinition.DEFAULT,
                                        ctx -> insertPropagationRow(1L))
                                .then(transactionOperations.inTransaction(
                                        TransactionDefinition.DEFAULT.with(Propagation.NESTED),
                                        ctx -> insertPropagationRow(2L)))
                                .then(transactionOperations.inTransaction(TransactionDefinition.requiresNew(),
                                        ctx -> insertPropagationRow(3L)))
                                .then(transactionOperations.inTransaction(
                                        TransactionDefinition.DEFAULT.with(Propagation.SUPPORTS),
                                        ctx -> insertPropagationRow(4L)))
                                .then(transactionOperations.inTransaction(
                                        TransactionDefinition.DEFAULT.with(Propagation.NOT_SUPPORTED),
                                        ctx -> insertPropagationRow(5L)))
                                .then(transactionOperations.inTransaction(
                                        TransactionDefinition.DEFAULT.with(Propagation.NEVER),
                                        ctx -> insertPropagationRow(6L)))))
                .expectNextCount(1)
                .verifyComplete();
        assertEquals(4, counting.creates(),
                "read connection 하나와 REQUIRED/NESTED/REQUIRES_NEW의 owned transaction 세 개만 acquire해야 한다");

        StepVerifier.create(executor.queryOne(
                        new SqlStatement("select count(*) as cnt from propagation_rows", List.of()),
                        row -> row.get("cnt", Long.class)))
                .expectNext(6L)
                .verifyComplete();

        StepVerifier.create(transactionManager.withConnection(
                        transactionOperations.inTransaction(TransactionDefinition.DEFAULT,
                                        ctx -> insertPropagationRow(7L)
                                                .then(Mono.error(new IllegalStateException("rollback"))))
                                .onErrorResume(IllegalStateException.class, ignored -> Mono.empty())))
                .verifyComplete();

        StepVerifier.create(executor.queryOne(
                        new SqlStatement("select count(*) as cnt from propagation_rows", List.of()),
                        row -> row.get("cnt", Long.class)))
                .expectNext(6L)
                .verifyComplete();

        StepVerifier.create(transactionManager.withConnection(
                        transactionOperations.inTransaction(
                                TransactionDefinition.DEFAULT.with(Propagation.MANDATORY),
                                ctx -> Mono.empty())))
                .expectError(IllegalStateException.class)
                .verify();
    }

    private Mono<Long> insertPropagationRow(long id) {
        return executor.execute(new SqlStatement(
                "insert into propagation_rows (id) values (?)", List.of(id)));
    }

    private static final class CountingConnectionFactory implements ConnectionFactory {
        private final ConnectionFactory delegate;
        private final AtomicInteger creates = new AtomicInteger();

        CountingConnectionFactory(ConnectionFactory delegate) {
            this.delegate = delegate;
        }

        @Override
        public Publisher<? extends Connection> create() {
            return Mono.from(delegate.create()).doOnSubscribe(ignored -> creates.incrementAndGet());
        }

        @Override
        public ConnectionFactoryMetadata getMetadata() {
            return delegate.getMetadata();
        }

        int creates() {
            return creates.get();
        }

        void reset() {
            creates.set(0);
        }
    }

    @Entity
    @Table(name = "person")
    public static class Person {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;
        private String name;

        public Person() {
        }

        public Person(String name) {
            this.name = name;
        }

        public Long getId() {
            return id;
        }

        public String getName() {
            return name;
        }
    }
}
