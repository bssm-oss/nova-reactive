package io.nova.r2dbc.integration;

import io.nova.core.EntityStateDetector;
import io.nova.core.SimpleReactiveEntityOperations;
import io.nova.dialect.h2.H2Dialect;
import io.nova.metadata.DefaultNamingStrategy;
import io.nova.metadata.EntityMetadataFactory;
import io.nova.query.Criteria;
import io.nova.query.QuerySpec;
import io.nova.r2dbc.R2dbcSqlExecutor;
import io.nova.r2dbc.R2dbcTransactionContext;
import io.nova.r2dbc.R2dbcTransactionManager;
import io.nova.r2dbc.integration.IntegrationFixtures.LockedAccount;
import io.nova.sql.SqlStatement;
import io.nova.tx.SimpleReactiveTransactionOperations;
import io.nova.tx.TransactionDefinition;
import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Pessimistic lock ({@link io.nova.query.LockMode#FOR_UPDATE})이 실제 H2에서 row-level lock을
 * 획득해 다른 connection의 동일 row 락 시도를 timeout/error로 차단하는지 검증한다.
 *
 * <p>단일 connection으로는 lock 충돌을 재현할 수 없으므로, 같은 in-memory DB를 가리키는 두
 * connection factory를 만들어 한쪽 transaction이 FOR UPDATE로 락을 점유한 상태에서 다른
 * connection이 같은 row의 락을 요청하도록 한다. H2는 {@code LOCK_TIMEOUT}을 ms 단위로
 * 지정할 수 있으므로 짧게 설정해 테스트 latency를 제한한다.
 *
 * <p>두 번째 connection은 R2DBC SPI를 직접 호출한다 — {@link R2dbcSqlExecutor}는 Reactor
 * {@code Context}의 {@code CONNECTION_KEY}를 통해 외부 transaction connection을 자동으로
 * 재사용하므로, 같은 reactive chain 안에서 호출하면 락 충돌을 재현할 수 없기 때문이다.
 */
class LockModeIntegrationTest {
    /**
     * 두 ConnectionFactory가 같은 in-memory DB를 가리키도록 같은 db 이름을 공유한다.
     */
    private ConnectionFactory primary;
    private ConnectionFactory secondary;
    private R2dbcSqlExecutor primaryExecutor;
    private R2dbcTransactionManager primaryTxManager;
    private SimpleReactiveEntityOperations primaryOperations;

    @BeforeEach
    void setUp() {
        String sharedDbName = "novalock_" + UUID.randomUUID().toString().replace("-", "");
        // LOCK_TIMEOUT은 ms. 200ms 정도면 락 충돌을 신속하게 검출하면서도
        // CI에서 우발적 false negative 없이 안정적이다.
        // r2dbc-h2 URL parser는 query string에서 '&'로 옵션을 구분한다 ('?key=v&key2=v2').
        String sharedUrl = "r2dbc:h2:mem:///" + sharedDbName + "?DB_CLOSE_DELAY=-1&LOCK_TIMEOUT=200";
        primary = ConnectionFactories.get(sharedUrl);
        secondary = ConnectionFactories.get(sharedUrl);

        H2Dialect dialect = new H2Dialect();
        EntityMetadataFactory metadataFactory = new EntityMetadataFactory(new DefaultNamingStrategy());

        primaryExecutor = new R2dbcSqlExecutor(primary, dialect);
        primaryTxManager = new R2dbcTransactionManager(primary);
        primaryOperations = new SimpleReactiveEntityOperations(
                metadataFactory,
                dialect,
                primaryExecutor,
                new EntityStateDetector(),
                new SimpleReactiveTransactionOperations(primaryTxManager));

        // schema 생성 + 단일 seed row 삽입은 primary로 실행한다.
        StepVerifier.create(primaryExecutor.execute(new SqlStatement(
                        "create table \"locked_accounts\" ("
                                + "\"id\" bigint primary key, "
                                + "\"email_address\" varchar(255), "
                                + "\"balance_cents\" bigint not null)",
                        List.of())))
                .expectNextCount(1)
                .verifyComplete();
        StepVerifier.create(primaryExecutor.execute(new SqlStatement(
                        "insert into \"locked_accounts\" (\"id\", \"email_address\", \"balance_cents\") "
                                + "values (?, ?, ?)",
                        List.of(1L, "owner@nova.io", 1000L))))
                .expectNextCount(1)
                .verifyComplete();
    }

    @Test
    void forUpdateRendersValidSqlAcceptedByH2Server() {
        // 트랜잭션 안에서 FOR UPDATE select가 실패 없이 row를 반환해야 한다.
        // H2가 dialect의 lock 절(" for update")을 거부하면 여기서 syntax error로 터진다.
        QuerySpec forUpdate = QuerySpec.empty()
                .where(Criteria.eq("id", 1L))
                .forUpdate();

        Mono<LockedAccount> work = primaryOperations.inTransaction(ops ->
                ops.findAll(LockedAccount.class, forUpdate).next());

        StepVerifier.create(work)
                .assertNext(loaded -> {
                    assertEquals(1L, loaded.getId());
                    assertEquals("owner@nova.io", loaded.getEmail());
                    assertEquals(1000L, loaded.getBalanceCents());
                })
                .verifyComplete();
    }

    @Test
    void forUpdateAcquiresRowLockBlockingConcurrentLockAttempt() {
        // primary tx가 FOR UPDATE로 락을 잡은 채로, secondary connection이 같은 row를
        // FOR UPDATE select 시도. H2는 LOCK_TIMEOUT(200ms) 초과 시 R2dbcException을 던진다.
        // secondary는 R2DBC SPI를 직접 호출해 reactor Context의 CONNECTION_KEY 자동 재사용을 우회한다.
        AtomicReference<Throwable> secondaryError = new AtomicReference<>();
        Mono<Void> primaryWork = primaryTxManager.inTransaction(TransactionDefinition.DEFAULT, ctx ->
                primaryExecutor.queryOne(
                                new SqlStatement(
                                        "select \"id\" from \"locked_accounts\" where \"id\" = ? for update",
                                        List.of(1L)),
                                row -> row.get("id", Long.class))
                        .then(secondaryLockAttempt()
                                .doOnError(secondaryError::set)
                                .onErrorResume(e -> Mono.empty())
                                .then())
        );

        // primary tx 자체는 정상 commit으로 끝나야 한다.
        StepVerifier.create(primaryWork)
                .expectComplete()
                .verify(Duration.ofSeconds(5));

        Throwable error = secondaryError.get();
        assertNotNull(error,
                "secondary connection의 FOR UPDATE 시도는 primary tx의 row lock 때문에 LOCK_TIMEOUT 에러로 실패해야 한다");
    }

    /**
     * 별도 raw r2dbc connection으로 FOR UPDATE select를 시도한다. {@link R2dbcSqlExecutor}를
     * 거치지 않으므로 Reactor Context의 CONNECTION_KEY를 자동 상속하지 않는다.
     */
    private Mono<Long> secondaryLockAttempt() {
        return Mono.usingWhen(
                Mono.from(secondary.create()),
                conn -> Mono.from(conn.beginTransaction())
                        .then(Mono.from(conn.createStatement(
                                "select \"id\" from \"locked_accounts\" where \"id\" = ? for update")
                                .bind(0, 1L)
                                .execute()))
                        .flatMap(result -> Mono.from(result.map((row, meta) -> row.get("id", Long.class)))),
                conn -> Mono.from(conn.rollbackTransaction())
                        .onErrorResume(ignored -> Mono.empty())
                        .then(Mono.from(conn.close())));
    }

    @Test
    void forUpdateReleasesLockAfterCommitAllowingSubsequentLock() {
        QuerySpec forUpdate = QuerySpec.empty()
                .where(Criteria.eq("id", 1L))
                .forUpdate();

        // 첫 tx에서 락을 점유했다가 commit으로 풀고, 두 번째 tx가 락 없이 정상 통과하는지 확인한다.
        Mono<Long> first = primaryOperations.inTransaction(ops ->
                ops.findAll(LockedAccount.class, forUpdate).next()
                        .map(LockedAccount::getId));
        Mono<Long> second = primaryOperations.inTransaction(ops ->
                ops.findAll(LockedAccount.class, forUpdate).next()
                        .map(LockedAccount::getId));

        StepVerifier.create(first.then(second))
                .expectNext(1L)
                .verifyComplete();
    }

    @Test
    void contextResourceExposesActiveR2dbcConnectionInsideForUpdateTransaction() {
        // FOR UPDATE 경로에서 ctx.resource()가 R2DBC Connection을 노출해야 한다 — connection
        // leak 검증을 위해 인프라적 invariant도 함께 본다.
        Mono<Boolean> active = primaryTxManager.inTransaction(TransactionDefinition.DEFAULT, ctx -> {
            Connection conn = ((R2dbcTransactionContext) ctx).connection();
            return Flux.from(conn.createStatement(
                            "select \"id\" from \"locked_accounts\" where \"id\" = ? for update")
                            .bind(0, 1L)
                            .execute())
                    .flatMap(result -> Mono.from(result.map((row, meta) -> row.get("id", Long.class))))
                    .next()
                    .map(id -> id == 1L);
        });

        StepVerifier.create(active).expectNext(Boolean.TRUE).verifyComplete();
    }
}
