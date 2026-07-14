package io.nova.r2dbc.integration;

import io.nova.core.ReactiveEntityManager;
import io.nova.core.SimpleReactiveEntityManager;
import io.nova.core.SqlExecutionListener;
import io.nova.exception.OptimisticLockingFailureException;
import io.nova.sql.SqlStatement;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.LockModeType;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ReactiveEntityManager}의 JPA 잠금(LockModeType) 계층이 <b>복합키</b>({@code @EmbeddedId}/{@code @IdClass})
 * entity에서도 실제 H2 driver 위에서 성립하는지 검증한다. W3 이전에는 잠금/버전 조회가 단일 {@code @Id}만 가정해
 * 복합키를 미지원으로 거부했다 — 이제는 모든 {@code @Id} 컴포넌트를 {@code c1 = ? and c2 = ?}로 결합한 술어로
 * FOR UPDATE 재조회와 낙관락 버전 검증을 수행한다.
 */
class EntityManagerCompositeKeyLockIntegrationTest {

    private final CapturingListener listener = new CapturingListener();

    private EntityManagerHarness harness() {
        H2IntegrationTestSupport support = H2IntegrationTestSupport.createWithManagedTransactions(listener);
        ReactiveEntityManager manager =
                new SimpleReactiveEntityManager(support.operations(), support.metadataFactory());
        return new EntityManagerHarness(support, manager);
    }

    // -------------------------------------------------------------------------------------------
    // @EmbeddedId
    // -------------------------------------------------------------------------------------------

    @Test
    void embeddedIdFindWithPessimisticWriteRoundTripsAndIssuesForUpdate() {
        EntityManagerHarness h = harness();
        h.support.execute(h.support.operations().createTableSql(SeatReservation.class));

        SeatId key = new SeatId("A", 1);
        StepVerifier.create(h.support.operations().save(new SeatReservation(key, "alice")))
                .expectNextCount(1).verifyComplete();

        listener.clear();
        StepVerifier.create(h.manager.find(SeatReservation.class, new SeatId("A", 1), LockModeType.PESSIMISTIC_WRITE))
                .assertNext(found -> {
                    assertEquals("A", found.getId().getSection());
                    assertEquals(1, found.getId().getSeatNo());
                    assertEquals("alice", found.getHolder());
                })
                .verifyComplete();

        assertTrue(listener.anyMatches("for update"),
                "복합키 PESSIMISTIC_WRITE find는 FOR UPDATE 절을 발행해야 한다. 실행 SQL=" + listener.snapshot());
    }

    @Test
    void embeddedIdLockedFindMatchesOnAllKeyComponentsNotJustTheFirst() {
        EntityManagerHarness h = harness();
        h.support.execute(h.support.operations().createTableSql(SeatReservation.class));

        // 첫 컴포넌트(section='A')를 공유하는 sibling 두 행 — WHERE가 seat_no까지 걸지 않으면 둘이 구분되지 않아
        // 한 잠금 재조회가 잘못된 행을 반환한다(단일-행 fixture로는 이 결함이 드러나지 않는다).
        StepVerifier.create(
                        h.support.operations().save(new SeatReservation(new SeatId("A", 1), "alice"))
                                .then(h.support.operations().save(new SeatReservation(new SeatId("A", 2), "bob"))))
                .expectNextCount(1).verifyComplete();

        // 각 복합키가 정확히 자기 행으로만 해석돼야 한다. 첫 컴포넌트만 매칭하면 두 find가 같은 행을 반환해
        // 아래 두 단언 중 하나는 반드시 깨진다(행 순서와 무관한 teeth).
        StepVerifier.create(h.manager.find(SeatReservation.class, new SeatId("A", 1), LockModeType.PESSIMISTIC_WRITE))
                .assertNext(found -> assertEquals("alice", found.getHolder()))
                .verifyComplete();
        StepVerifier.create(h.manager.find(SeatReservation.class, new SeatId("A", 2), LockModeType.PESSIMISTIC_WRITE))
                .assertNext(found -> assertEquals("bob", found.getHolder()))
                .verifyComplete();
    }

    @Test
    void embeddedIdOptimisticLockSucceedsWhenVersionMatches() {
        EntityManagerHarness h = harness();
        h.support.execute(h.support.operations().createTableSql(SeatReservation.class));

        SeatReservation reservation = new SeatReservation(new SeatId("B", 2), "bob");
        StepVerifier.create(h.support.operations().save(reservation)).expectNextCount(1).verifyComplete();

        // save가 version을 0으로 초기화한 그 인스턴스(=DB 버전과 일치)로 optimistic 검증 → 성공.
        StepVerifier.create(h.manager.lock(reservation, LockModeType.OPTIMISTIC))
                .verifyComplete();
    }

    @Test
    void embeddedIdOptimisticLockFailsWhenVersionStale() {
        EntityManagerHarness h = harness();
        h.support.execute(h.support.operations().createTableSql(SeatReservation.class));

        SeatReservation reservation = new SeatReservation(new SeatId("C", 3), "carol");
        StepVerifier.create(h.support.operations().save(reservation)).expectNextCount(1).verifyComplete();

        // DB에 없는 stale version을 든 인스턴스로 lock → 복합키 (id AND version) 매치 실패.
        SeatReservation stale = new SeatReservation(new SeatId("C", 3), "carol", 999L);
        StepVerifier.create(h.manager.lock(stale, LockModeType.OPTIMISTIC))
                .expectError(OptimisticLockingFailureException.class)
                .verify();
    }

    // -------------------------------------------------------------------------------------------
    // @IdClass
    // -------------------------------------------------------------------------------------------

    @Test
    void idClassFindWithPessimisticWriteRoundTripsAndIssuesForUpdate() {
        EntityManagerHarness h = harness();
        h.support.execute(h.support.operations().createTableSql(Ticket.class));

        StepVerifier.create(h.support.operations().save(new Ticket(10L, "X1", "dave")))
                .expectNextCount(1).verifyComplete();

        listener.clear();
        StepVerifier.create(h.manager.find(Ticket.class, new TicketId(10L, "X1"), LockModeType.PESSIMISTIC_WRITE))
                .assertNext(found -> {
                    assertEquals(10L, found.getEventId());
                    assertEquals("X1", found.getCode());
                    assertEquals("dave", found.getHolder());
                })
                .verifyComplete();

        assertTrue(listener.anyMatches("for update"),
                "복합키 PESSIMISTIC_WRITE find는 FOR UPDATE 절을 발행해야 한다. 실행 SQL=" + listener.snapshot());
    }

    @Test
    void idClassLockedFindMatchesOnAllKeyComponentsNotJustTheFirst() {
        EntityManagerHarness h = harness();
        h.support.execute(h.support.operations().createTableSql(Ticket.class));

        // 첫 컴포넌트(event_id=10)를 공유하는 sibling 두 행 — WHERE가 code까지 걸지 않으면 잠금 재조회가
        // 잘못된 행을 반환한다.
        StepVerifier.create(
                        h.support.operations().save(new Ticket(10L, "X1", "dave"))
                                .then(h.support.operations().save(new Ticket(10L, "X2", "erin"))))
                .expectNextCount(1).verifyComplete();

        StepVerifier.create(h.manager.find(Ticket.class, new TicketId(10L, "X1"), LockModeType.PESSIMISTIC_WRITE))
                .assertNext(found -> assertEquals("dave", found.getHolder()))
                .verifyComplete();
        StepVerifier.create(h.manager.find(Ticket.class, new TicketId(10L, "X2"), LockModeType.PESSIMISTIC_WRITE))
                .assertNext(found -> assertEquals("erin", found.getHolder()))
                .verifyComplete();
    }

    @Test
    void idClassOptimisticLockSucceedsWhenVersionMatches() {
        EntityManagerHarness h = harness();
        h.support.execute(h.support.operations().createTableSql(Ticket.class));

        Ticket ticket = new Ticket(20L, "Y2", "erin");
        StepVerifier.create(h.support.operations().save(ticket)).expectNextCount(1).verifyComplete();

        StepVerifier.create(h.manager.lock(ticket, LockModeType.OPTIMISTIC))
                .verifyComplete();
    }

    @Test
    void idClassOptimisticLockFailsWhenVersionStale() {
        EntityManagerHarness h = harness();
        h.support.execute(h.support.operations().createTableSql(Ticket.class));

        Ticket ticket = new Ticket(30L, "Z3", "frank");
        StepVerifier.create(h.support.operations().save(ticket)).expectNextCount(1).verifyComplete();

        Ticket stale = new Ticket(30L, "Z3", "frank", 999L);
        StepVerifier.create(h.manager.lock(stale, LockModeType.OPTIMISTIC))
                .expectError(OptimisticLockingFailureException.class)
                .verify();
    }

    private record EntityManagerHarness(H2IntegrationTestSupport support, ReactiveEntityManager manager) {
    }

    /**
     * 실행된 SQL 문을 순서대로(소문자로) 기록하는 리스너.
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

        List<String> snapshot() {
            return List.copyOf(statements);
        }
    }

    // -------------------------------------------------------------------------------------------
    // @EmbeddedId 복합키 + @Version fixture
    // -------------------------------------------------------------------------------------------

    @Embeddable
    public static class SeatId {
        @Column(name = "section")
        private String section;
        @Column(name = "seat_no")
        private Integer seatNo;

        public SeatId() {
        }

        public SeatId(String section, Integer seatNo) {
            this.section = section;
            this.seatNo = seatNo;
        }

        public String getSection() {
            return section;
        }

        public Integer getSeatNo() {
            return seatNo;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof SeatId that
                    && Objects.equals(section, that.section) && Objects.equals(seatNo, that.seatNo);
        }

        @Override
        public int hashCode() {
            return Objects.hash(section, seatNo);
        }
    }

    @Entity
    @Table(name = "seat_reservations")
    public static class SeatReservation {
        @EmbeddedId
        private SeatId id;
        @Column(name = "holder")
        private String holder;
        @Version
        private Long version;

        public SeatReservation() {
        }

        public SeatReservation(SeatId id, String holder) {
            this.id = id;
            this.holder = holder;
        }

        public SeatReservation(SeatId id, String holder, Long version) {
            this.id = id;
            this.holder = holder;
            this.version = version;
        }

        public SeatId getId() {
            return id;
        }

        public String getHolder() {
            return holder;
        }

        public Long getVersion() {
            return version;
        }
    }

    // -------------------------------------------------------------------------------------------
    // @IdClass 복합키 + @Version fixture
    // -------------------------------------------------------------------------------------------

    public static class TicketId {
        private Long eventId;
        private String code;

        public TicketId() {
        }

        public TicketId(Long eventId, String code) {
            this.eventId = eventId;
            this.code = code;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof TicketId that
                    && Objects.equals(eventId, that.eventId) && Objects.equals(code, that.code);
        }

        @Override
        public int hashCode() {
            return Objects.hash(eventId, code);
        }
    }

    @Entity
    @Table(name = "tickets")
    @IdClass(TicketId.class)
    public static class Ticket {
        @Id
        @Column(name = "event_id")
        private Long eventId;
        @Id
        private String code;
        @Column(name = "holder")
        private String holder;
        @Version
        private Long version;

        public Ticket() {
        }

        public Ticket(Long eventId, String code, String holder) {
            this.eventId = eventId;
            this.code = code;
            this.holder = holder;
        }

        public Ticket(Long eventId, String code, String holder, Long version) {
            this.eventId = eventId;
            this.code = code;
            this.holder = holder;
            this.version = version;
        }

        public Long getEventId() {
            return eventId;
        }

        public String getCode() {
            return code;
        }

        public String getHolder() {
            return holder;
        }

        public Long getVersion() {
            return version;
        }
    }
}
