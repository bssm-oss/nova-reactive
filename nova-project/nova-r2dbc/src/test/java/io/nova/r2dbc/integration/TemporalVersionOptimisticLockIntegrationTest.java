package io.nova.r2dbc.integration;

import io.nova.exception.OptimisticLockingFailureException;
import io.nova.r2dbc.integration.IntegrationFixtures.TimestampVersionedAccount;
import io.nova.sql.SqlStatement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 시간 {@code @Version}(LocalDateTime) optimistic locking이 실제 H2에서 왕복하는지 검증한다(드라이버 실증).
 *
 * <p>정수 버전과 달리 시간 버전은 update 시 현재 시각으로 갱신되며(증분 대신 now()), WHERE는 old 값을
 * 비교해 동시 update 충돌을 감지한다. 두 stale 인스턴스가 같은 timestamp로 update를 시도하면 한쪽은
 * 성공하고 다른 쪽은 {@link OptimisticLockingFailureException}으로 실패해야 한다.
 */
class TemporalVersionOptimisticLockIntegrationTest {
    private H2IntegrationTestSupport support;

    @BeforeEach
    void setUp() {
        support = H2IntegrationTestSupport.create();
        support.execute(support.operations().createTableSql(TimestampVersionedAccount.class));
    }

    @Test
    void initialSaveAssignsTimestampVersionAndGeneratesId() {
        TimestampVersionedAccount account = new TimestampVersionedAccount("first@nova.io");

        StepVerifier.create(support.operations().save(account))
                .assertNext(saved -> {
                    assertNotNull(saved.getId(), "IDENTITY id가 회수돼야 한다");
                    assertNotNull(saved.getVersion(), "save가 시간 version을 현재 시각으로 초기화해야 한다");
                })
                .verifyComplete();
    }

    @Test
    void successfulUpdateAdvancesTimestampVersionInDatabase() {
        TimestampVersionedAccount account = new TimestampVersionedAccount("update@nova.io");

        AtomicReference<Long> rowId = new AtomicReference<>();
        AtomicReference<LocalDateTime> initialVersion = new AtomicReference<>();
        Mono<TimestampVersionedAccount> pipeline = support.operations().save(account)
                .doOnNext(saved -> {
                    rowId.set(saved.getId());
                    initialVersion.set(saved.getVersion());
                })
                .flatMap(saved -> {
                    saved.setEmail("update-v2@nova.io");
                    return support.operations().save(saved);
                });

        StepVerifier.create(pipeline)
                .assertNext(updated -> assertNotNull(updated.getVersion()))
                .verifyComplete();

        // DB의 실제 version이 초기값보다 뒤여야 한다(현재 시각으로 갱신).
        StepVerifier.create(support.sqlExecutor().queryOne(
                        new SqlStatement(
                                "select \"version\" from \"timestamp_versioned_accounts\" where \"id\" = ?",
                                List.of(rowId.get())),
                        row -> row.get("version", LocalDateTime.class)))
                .assertNext(dbVersion -> assertFalse(dbVersion.isBefore(initialVersion.get()),
                        "update 후 DB version은 초기 version 이상(현재 시각으로 갱신)이어야 한다"))
                .verifyComplete();
    }

    @Test
    void concurrentUpdatesFailFastForStaleTimestampVersion() {
        TimestampVersionedAccount initial = new TimestampVersionedAccount("race@nova.io");

        AtomicReference<Long> rowId = new AtomicReference<>();
        AtomicReference<LocalDateTime> version0 = new AtomicReference<>();
        StepVerifier.create(support.operations().save(initial)
                        .doOnNext(saved -> {
                            rowId.set(saved.getId());
                            version0.set(saved.getVersion());
                        }))
                .expectNextCount(1)
                .verifyComplete();
        assertNotNull(rowId.get());
        assertNotNull(version0.get());

        // 같은 row를 두 stale 인스턴스로 동시 update — 둘 다 version0을 들고 시작.
        TimestampVersionedAccount staleA = new TimestampVersionedAccount(rowId.get(), "race-a@nova.io", version0.get());
        TimestampVersionedAccount staleB = new TimestampVersionedAccount(rowId.get(), "race-b@nova.io", version0.get());

        StepVerifier.create(support.operations().save(staleA))
                .assertNext(updated -> assertTrue(updated.getVersion() != null))
                .verifyComplete();

        // 두 번째 update는 version0 조건이 더 이상 매치되지 않아 낙관락 실패.
        StepVerifier.create(support.operations().save(staleB))
                .expectError(OptimisticLockingFailureException.class)
                .verify();

        // 최종적으로 첫 update의 email만 남아야 한다.
        StepVerifier.create(support.sqlExecutor().queryOne(
                        new SqlStatement(
                                "select \"email_address\" from \"timestamp_versioned_accounts\" where \"id\" = ?",
                                List.of(rowId.get())),
                        row -> row.get("email_address", String.class)))
                .expectNext("race-a@nova.io")
                .verifyComplete();
    }
}
