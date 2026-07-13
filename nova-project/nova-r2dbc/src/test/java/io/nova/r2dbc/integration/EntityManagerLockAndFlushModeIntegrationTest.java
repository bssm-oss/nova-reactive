package io.nova.r2dbc.integration;

import io.nova.core.ReactiveEntityManager;
import io.nova.core.SimpleReactiveEntityManager;
import io.nova.core.SqlExecutionListener;
import io.nova.exception.OptimisticLockingFailureException;
import io.nova.r2dbc.integration.IntegrationFixtures.VersionedAccount;
import io.nova.sql.SqlStatement;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FlushModeType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.LockModeType;
import jakarta.persistence.Table;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ReactiveEntityManager}의 JPA 잠금(LockModeType)/FlushMode 계층이 실제 H2 driver 위에서 성립하는지
 * 검증한다: find(PESSIMISTIC_WRITE)가 {@code FOR UPDATE}를 발행하고, lock(entity, OPTIMISTIC)이 버전을 검증하며,
 * FlushMode.COMMIT이 쿼리 전 auto-flush를 억제하는지(commit 시에만 flush) SQL 실행 순서로 고정한다.
 */
class EntityManagerLockAndFlushModeIntegrationTest {

    private final CapturingListener listener = new CapturingListener();

    private EntityManagerHarness harness() {
        H2IntegrationTestSupport support = H2IntegrationTestSupport.createWithManagedTransactions(listener);
        ReactiveEntityManager manager =
                new SimpleReactiveEntityManager(support.operations(), support.metadataFactory());
        return new EntityManagerHarness(support, manager);
    }

    // -------------------------------------------------------------------------------------------
    // find(Class, id, LockModeType) — PESSIMISTIC_WRITE는 FOR UPDATE 발행
    // -------------------------------------------------------------------------------------------

    @Test
    void findWithPessimisticWriteIssuesForUpdate() {
        EntityManagerHarness h = harness();
        h.support.execute(h.support.operations().createTableSql(VersionedAccount.class));

        VersionedAccount account = new VersionedAccount("lock@nova.io");
        StepVerifier.create(h.support.operations().save(account)).expectNextCount(1).verifyComplete();
        Long id = account.getId();

        listener.clear();
        StepVerifier.create(h.manager.find(VersionedAccount.class, id, LockModeType.PESSIMISTIC_WRITE))
                .assertNext(found -> assertTrue(found.getId().equals(id)))
                .verifyComplete();

        assertTrue(listener.anyMatches("for update"),
                "PESSIMISTIC_WRITE find는 FOR UPDATE 절을 발행해야 한다. 실행 SQL=" + listener.snapshot());
    }

    // -------------------------------------------------------------------------------------------
    // lock(entity, OPTIMISTIC) — 버전 검증
    // -------------------------------------------------------------------------------------------

    @Test
    void lockOptimisticSucceedsWhenVersionMatches() {
        EntityManagerHarness h = harness();
        h.support.execute(h.support.operations().createTableSql(VersionedAccount.class));

        VersionedAccount account = new VersionedAccount("opt@nova.io");
        StepVerifier.create(h.support.operations().save(account)).expectNextCount(1).verifyComplete();

        StepVerifier.create(h.manager.lock(account, LockModeType.OPTIMISTIC))
                .verifyComplete();
    }

    @Test
    void lockOptimisticFailsWhenVersionStale() {
        EntityManagerHarness h = harness();
        h.support.execute(h.support.operations().createTableSql(VersionedAccount.class));

        VersionedAccount account = new VersionedAccount("stale@nova.io");
        StepVerifier.create(h.support.operations().save(account)).expectNextCount(1).verifyComplete();

        // in-memory에서 stale version(존재하지 않는 값)을 든 인스턴스로 lock → 버전 불일치 실패.
        VersionedAccount stale = new VersionedAccount(account.getId(), "stale@nova.io", 999L);
        StepVerifier.create(h.manager.lock(stale, LockModeType.OPTIMISTIC))
                .expectError(OptimisticLockingFailureException.class)
                .verify();
    }

    @Test
    void lockOptimisticOnNonVersionedEntityFailsFast() {
        EntityManagerHarness h = harness();
        h.support.execute(h.support.operations().createTableSql(FlushProbe.class));

        FlushProbe probe = new FlushProbe("x");
        StepVerifier.create(h.support.operations().save(probe)).expectNextCount(1).verifyComplete();

        StepVerifier.create(h.manager.lock(probe, LockModeType.OPTIMISTIC))
                .expectError(IllegalArgumentException.class)
                .verify();
    }

    // -------------------------------------------------------------------------------------------
    // FlushMode.COMMIT — 쿼리 전 auto-flush 억제
    // -------------------------------------------------------------------------------------------

    @Test
    void flushModeAutoFlushesDirtyChangeBeforeQuery() {
        EntityManagerHarness h = harness();
        h.support.execute(h.support.operations().createTableSql(FlushProbe.class));
        Long id1 = seedProbe(h, "one");
        Long id2 = seedProbe(h, "two");

        listener.clear();
        // AUTO(기본): id1을 로드/수정한 뒤 id2를 쿼리하면 쿼리 전 auto-flush로 UPDATE(id1)가 SELECT(id2) 앞에 발행된다.
        StepVerifier.create(h.manager.inTransaction(e ->
                        e.find(FlushProbe.class, id1)
                                .flatMap(p -> {
                                    p.setName("dirty");
                                    return e.find(FlushProbe.class, id2);
                                })))
                .expectNextCount(1)
                .verifyComplete();

        int firstUpdate = listener.firstIndexMatching("update ");
        int lastSelect = listener.lastIndexMatching("select ");
        assertTrue(firstUpdate >= 0, "AUTO는 dirty 변경을 flush해야 한다. SQL=" + listener.snapshot());
        assertTrue(firstUpdate < lastSelect,
                "AUTO에서는 쿼리 전 auto-flush로 UPDATE가 마지막 SELECT 앞에 와야 한다. SQL=" + listener.snapshot());
    }

    @Test
    void flushModeCommitSuppressesPreQueryFlush() {
        EntityManagerHarness h = harness();
        h.support.execute(h.support.operations().createTableSql(FlushProbe.class));
        Long id1 = seedProbe(h, "one");
        Long id2 = seedProbe(h, "two");

        listener.clear();
        ReactiveEntityManager committing = h.manager.setFlushMode(FlushModeType.COMMIT);
        // COMMIT: id1 수정 후 id2 쿼리 시 쿼리 전 flush가 억제되어야 한다 → UPDATE(id1)는 모든 SELECT 뒤(commit 시)에 온다.
        StepVerifier.create(committing.inTransaction(e ->
                        e.find(FlushProbe.class, id1)
                                .flatMap(p -> {
                                    p.setName("dirty");
                                    return e.find(FlushProbe.class, id2);
                                })))
                .expectNextCount(1)
                .verifyComplete();

        int firstUpdate = listener.firstIndexMatching("update ");
        int lastSelect = listener.lastIndexMatching("select ");
        assertTrue(firstUpdate >= 0, "COMMIT도 commit 시점에는 flush해야 한다(변경 유실 금지). SQL=" + listener.snapshot());
        assertTrue(firstUpdate > lastSelect,
                "COMMIT은 쿼리 전 auto-flush를 억제해 UPDATE가 모든 SELECT 뒤(commit)에 와야 한다. SQL=" + listener.snapshot());
        assertFalse(committing == h.manager, "setFlushMode는 functional하게 새 매니저를 돌려준다");
    }

    private Long seedProbe(EntityManagerHarness h, String name) {
        FlushProbe probe = new FlushProbe(name);
        StepVerifier.create(h.support.operations().save(probe)).expectNextCount(1).verifyComplete();
        return probe.getId();
    }

    private record EntityManagerHarness(H2IntegrationTestSupport support, ReactiveEntityManager manager) {
    }

    /**
     * 실행된 SQL 문을 순서대로 기록하는 리스너.
     */
    private static final class CapturingListener implements SqlExecutionListener {
        private final List<String> statements = new CopyOnWriteArrayList<>();

        @Override
        public void onBeforeExecution(SqlStatement statement) {
            statements.add(statement.sql().toLowerCase());
        }

        void clear() {
            statements.clear();
        }

        boolean anyMatches(String needle) {
            return statements.stream().anyMatch(s -> s.contains(needle));
        }

        int firstIndexMatching(String needle) {
            for (int i = 0; i < statements.size(); i++) {
                if (statements.get(i).contains(needle)) {
                    return i;
                }
            }
            return -1;
        }

        int lastIndexMatching(String needle) {
            int found = -1;
            for (int i = 0; i < statements.size(); i++) {
                if (statements.get(i).contains(needle)) {
                    found = i;
                }
            }
            return found;
        }

        List<String> snapshot() {
            return List.copyOf(statements);
        }
    }

    @Entity
    @Table(name = "flush_probe")
    public static class FlushProbe {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;

        @Column(name = "name")
        private String name;

        public FlushProbe() {
        }

        public FlushProbe(String name) {
            this.name = name;
        }

        public Long getId() {
            return id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }
}
