package io.nova.r2dbc.integration;

import io.nova.exception.OptimisticLockingFailureException;
import io.nova.r2dbc.integration.IntegrationFixtures.VersionedAccount;
import io.nova.sql.SqlStatement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * {@code @Version} entity의 optimistic locking이 실제 H2에서 동작하는지 검증한다.
 *
 * <p>두 in-memory {@link VersionedAccount} 인스턴스가 같은 row를 stale version으로 update할 때
 * 한쪽은 성공·다른 쪽은 {@link OptimisticLockingFailureException}으로 실패해야 한다.
 */
class OptimisticLockIntegrationTest {
    private H2IntegrationTestSupport support;

    @BeforeEach
    void setUp() {
        support = H2IntegrationTestSupport.create();
        // dialect schema generator로 DDL을 만든다 — IDENTITY 컬럼은 H2가 그대로 받아들인다.
        support.execute(support.operations().createTableSql(VersionedAccount.class));
    }

    @Test
    void initialSaveAssignsVersionZeroAndGeneratesId() {
        VersionedAccount account = new VersionedAccount("first@nova.io");

        StepVerifier.create(support.operations().save(account))
                .assertNext(saved -> {
                    assertNotNull(saved.getId(), "IDENTITY id가 RETURNING으로 회수되어야 한다");
                    assertNotNull(saved.getVersion(), "save가 entity.version을 0으로 초기화해야 한다");
                    assertEquals(0L, saved.getVersion());
                })
                .verifyComplete();
    }

    @Test
    void successfulUpdateIncrementsVersion() {
        VersionedAccount account = new VersionedAccount("update@nova.io");

        AtomicReference<Long> generatedId = new AtomicReference<>();
        Mono<VersionedAccount> pipeline = support.operations().save(account)
                .doOnNext(saved -> generatedId.set(saved.getId()))
                .flatMap(saved -> {
                    // 같은 entity instance를 다시 save → 동일 row UPDATE 경로로 들어간다.
                    saved.setEmail("update-v2@nova.io");
                    return support.operations().save(saved);
                });

        StepVerifier.create(pipeline)
                .assertNext(updated -> {
                    assertEquals(1L, updated.getVersion(), "save 두 번 호출 시 version은 0 → 1로 증가해야 한다");
                })
                .verifyComplete();

        assertNotNull(generatedId.get());
        // DB의 실제 version 값도 1이어야 한다.
        StepVerifier.create(support.sqlExecutor().queryOne(
                        new SqlStatement(
                                "select \"version\" from \"versioned_accounts\" where \"id\" = ?",
                                List.of(generatedId.get())),
                        row -> row.get("version", Long.class)))
                .expectNext(1L)
                .verifyComplete();
    }

    @Test
    void concurrentUpdatesFailFastForStaleVersion() {
        VersionedAccount initial = new VersionedAccount("race@nova.io");

        // Step 1: 초기 insert (version 0, generated id).
        AtomicReference<Long> rowId = new AtomicReference<>();
        StepVerifier.create(support.operations().save(initial)
                        .doOnNext(saved -> rowId.set(saved.getId())))
                .expectNextCount(1)
                .verifyComplete();
        assertNotNull(rowId.get());

        // Step 2: 같은 row를 두 stale 인스턴스로 동시 update — 둘 다 version=0L을 들고 시작.
        // 첫 update는 성공해 DB version이 1로 바뀌고, 두 번째 update는 version=0L 조건 매치에
        // 실패해 affected=0 → OptimisticLockingFailureException.
        VersionedAccount staleA = new VersionedAccount(rowId.get(), "race-a@nova.io", 0L);
        VersionedAccount staleB = new VersionedAccount(rowId.get(), "race-b@nova.io", 0L);

        StepVerifier.create(support.operations().save(staleA))
                .assertNext(updated -> assertEquals(1L, updated.getVersion()))
                .verifyComplete();

        StepVerifier.create(support.operations().save(staleB))
                .expectError(OptimisticLockingFailureException.class)
                .verify();

        // 최종적으로 첫 update의 결과(email = race-a@nova.io, version = 1)만 남아 있어야 한다.
        StepVerifier.create(support.sqlExecutor().queryOne(
                        new SqlStatement(
                                "select \"email_address\", \"version\" from \"versioned_accounts\" where \"id\" = ?",
                                List.of(rowId.get())),
                        row -> new Object[]{row.get("email_address", String.class), row.get("version", Long.class)}))
                .assertNext(values -> {
                    assertEquals("race-a@nova.io", values[0]);
                    assertEquals(1L, values[1]);
                })
                .verifyComplete();
    }

    @Test
    void updateAgainstMissingRowSignalsOptimisticLockingFailure() {
        // 사전에 어떤 row도 insert하지 않았다. id=99 row에 대한 update는 affected=0이 되고
        // OptimisticLockingFailureException으로 fail해야 한다.
        VersionedAccount nonExistent = new VersionedAccount(99L, "ghost@nova.io", 5L);

        StepVerifier.create(support.operations().save(nonExistent))
                .expectError(OptimisticLockingFailureException.class)
                .verify();
    }
}
