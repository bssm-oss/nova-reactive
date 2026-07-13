package io.nova.r2dbc.integration;

import io.nova.core.ReactiveEntityManager;
import io.nova.core.SimpleReactiveEntityManager;
import io.nova.exception.OptimisticLockingFailureException;
import io.nova.r2dbc.integration.IntegrationFixtures.TimestampVersionedAccount;
import io.nova.sql.SqlStatement;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    /**
     * 회귀 테스트(single-read): save로 받은 managed 인스턴스를 연속으로 두 번 update한다. 수정 전에는
     * renderer(SET)와 operations(writeback)가 각자 독립 now()를 읽어 in-memory version이 DB보다 앞서
     * 두 번째 update의 {@code WHERE version=<in-memory>}가 DB와 안 맞아 <em>동시 쓰기 없이도</em>
     * false-positive {@link OptimisticLockingFailureException}으로 실패했다. 수정 후에는 성공해야 한다.
     */
    @Test
    void updateThenUpdateOnSameManagedInstanceSucceeds() {
        TimestampVersionedAccount account = new TimestampVersionedAccount("seq@nova.io");

        AtomicReference<Long> rowId = new AtomicReference<>();
        Mono<TimestampVersionedAccount> pipeline = support.operations().save(account)
                .doOnNext(saved -> rowId.set(saved.getId()))
                .flatMap(saved -> {
                    saved.setEmail("v1@nova.io");
                    return support.operations().save(saved);
                })
                .flatMap(saved -> {
                    saved.setEmail("v2@nova.io");
                    return support.operations().save(saved); // 같은 인스턴스로 두 번째 update
                });

        StepVerifier.create(pipeline)
                .assertNext(twiceUpdated -> assertEquals("v2@nova.io", twiceUpdated.getEmail()))
                .verifyComplete();

        StepVerifier.create(support.sqlExecutor().queryOne(
                        new SqlStatement(
                                "select \"email_address\" from \"timestamp_versioned_accounts\" where \"id\" = ?",
                                List.of(rowId.get())),
                        row -> row.get("email_address", String.class)))
                .expectNext("v2@nova.io")
                .verifyComplete();
    }

    /**
     * 회귀 테스트: update 직후 같은 인스턴스로 delete(version check)가 성공해야 한다. 수정 전에는 in-memory
     * version이 DB보다 앞서 delete의 {@code WHERE version}이 안 맞아 false-positive로 실패했다.
     */
    @Test
    void deleteAfterUpdateOnSameManagedInstanceSucceeds() {
        TimestampVersionedAccount account = new TimestampVersionedAccount("del@nova.io");

        Mono<Long> pipeline = support.operations().save(account)
                .flatMap(saved -> {
                    saved.setEmail("v1@nova.io");
                    return support.operations().save(saved);
                })
                .flatMap(saved -> support.operations().delete(saved));

        StepVerifier.create(pipeline)
                .assertNext(affected -> assertEquals(1L, affected, "update 직후 delete는 성공(affected=1)해야 한다"))
                .verifyComplete();
    }

    /**
     * 회귀 테스트: update 직후 같은 인스턴스로 lock(OPTIMISTIC)이 성공해야 한다. lock의 버전 검증은
     * {@code WHERE id AND version=<in-memory>} 존재 쿼리라 in-memory==DB여야 통과한다(수정 전이면 false-positive).
     */
    @Test
    void lockOptimisticAfterUpdateOnSameManagedInstanceSucceeds() {
        ReactiveEntityManager em =
                new SimpleReactiveEntityManager(support.operations(), support.metadataFactory());
        TimestampVersionedAccount account = new TimestampVersionedAccount("lock@nova.io");

        StepVerifier.create(support.operations().save(account)
                        .flatMap(saved -> {
                            saved.setEmail("v1@nova.io");
                            return support.operations().save(saved);
                        })
                        .flatMap(saved -> em.lock(saved, LockModeType.OPTIMISTIC).thenReturn(saved)))
                .expectNextCount(1)
                .verifyComplete();
    }

    /**
     * 회귀 테스트(monotonic): 벽시계가 old보다 앞서지 않아도 새 version은 저장 해상도에서 strictly 증가해야 한다.
     * DB/entity version을 미래 시각으로 강제한 뒤 update하면, {@code now() <= old}이므로 수정 전(단순 now())에는
     * version이 과거로 후퇴(= 동시 tx가 같은 tick에서 old를 재사용하는 silent lost-update의 근본 원인)했다.
     * monotonic 규칙(old + 1μs)으로 version이 반드시 old를 넘어서는지 검증한다.
     */
    @Test
    void versionAdvancesStrictlyEvenWhenClockNotAhead() {
        TimestampVersionedAccount account = new TimestampVersionedAccount("mono@nova.io");
        AtomicReference<Long> rowId = new AtomicReference<>();
        StepVerifier.create(support.operations().save(account).doOnNext(saved -> rowId.set(saved.getId())))
                .expectNextCount(1)
                .verifyComplete();

        // DB version을 미래 시각으로 강제(now() < future 조건을 결정적으로 만든다).
        LocalDateTime future = LocalDateTime.now().plusHours(1).truncatedTo(ChronoUnit.MICROS);
        StepVerifier.create(support.sqlExecutor().execute(new SqlStatement(
                        "update \"timestamp_versioned_accounts\" set \"version\" = ? where \"id\" = ?",
                        List.of(future, rowId.get()))))
                .expectNext(1L)
                .verifyComplete();

        // future version을 든 인스턴스로 update → monotonic이면 next = future + 1μs.
        TimestampVersionedAccount stale = new TimestampVersionedAccount(rowId.get(), "advance@nova.io", future);
        StepVerifier.create(support.operations().save(stale))
                .expectNextCount(1)
                .verifyComplete();

        StepVerifier.create(support.sqlExecutor().queryOne(
                        new SqlStatement(
                                "select \"version\" from \"timestamp_versioned_accounts\" where \"id\" = ?",
                                List.of(rowId.get())),
                        row -> row.get("version", LocalDateTime.class)))
                .assertNext(dbVersion -> assertTrue(dbVersion.isAfter(future),
                        "monotonic: 벽시계가 old보다 앞서지 않아도 version은 strictly 증가해야 한다(actual=" + dbVersion + ")"))
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
