package io.nova.r2dbc.integration;

import io.nova.core.SqlExecutionListener;
import io.nova.exception.OptimisticLockingFailureException;
import io.nova.query.NativeQuery;
import io.nova.schema.SchemaInitializer;
import io.nova.schema.SimpleSchemaInitializer;
import io.nova.sql.SqlStatement;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinColumns;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code SimpleReactiveEntityOperations.ensurePrimaryVersionPresent}의 <b>복합키</b> 선검증을 H2 end-to-end로
 * 못박는 회귀 테스트다. versioned 엔티티가 소유 컬렉션(여기서는 {@code @OneToMany(orphanRemoval)})을 가진 채
 * hard delete 될 때, 비가역 owner-이전 정리 <em>앞에서</em> 실행되는 {@code (id AND version)} COUNT 선검증이
 * 복합키의 <b>모든</b> id 컴포넌트를 AND로 걸어야 한다. 예전엔 {@code idProperty()}(첫 컴포넌트만)로 술어를
 * 세워, 두 번째 컴포넌트만 다른 형제 행이 stale version을 우연히 만족하면 count가 오탐되어 낙관락 선검증이
 * 뚫렸다(→ 이후 성능/원자성 결함).
 *
 * <p><b>왜 SQL 캡처로 검증하는가.</b> Nova는 복합키 엔티티에 대해 소유 컬렉션 실체(@ElementCollection /
 * @ManyToMany)와 to-one 참조를 metadata build/실행 단계에서 거부/미지원한다. 따라서 복합키 owner가 컬렉션
 * 행을 실제로 물리 저장했다가 지우는 "row 상태" 시나리오는 구성 자체가 불가능하다. 대신 {@code @OneToMany
 * (orphanRemoval)} 마커만으로도 {@code irreversiblePreOwnerWork}가 참이 되어 <b>owner DELETE보다 먼저</b>
 * 버전 선검증 COUNT가 실행된다는 점을 이용해, 실행된 COUNT 문의 바인딩이 두 번째 id 컴포넌트를 포함하는지를
 * {@link SqlExecutionListener}로 직접 관찰한다. 첫 컴포넌트만 걸던 예전 술어라면 이 바인딩이 빠지고, 형제 행이
 * stale version을 만족해 선검증이 통과({@link OptimisticLockingFailureException} 대신 downstream 정리 실패)한다.
 */
class CompositeKeyVersionedDeleteVersionGuardIntegrationTest {

    /**
     * hard delete 경로에서 발행된 {@code select count(*)} 선검증 문을 바인딩과 함께 기록한다.
     */
    private static final class RecordingSqlListener implements SqlExecutionListener {
        private final List<SqlStatement> statements = new CopyOnWriteArrayList<>();

        @Override
        public void onBeforeExecution(SqlStatement statement) {
            statements.add(statement);
        }

        void clear() {
            statements.clear();
        }

        SqlStatement lastCount() {
            SqlStatement found = null;
            for (SqlStatement statement : statements) {
                if (statement.sql().toLowerCase(Locale.ROOT).contains("count(*)")) {
                    found = statement;
                }
            }
            return found;
        }
    }

    @Test
    void embeddedIdVersionGuardBindsEveryIdComponentAndRejectsStaleSibling() {
        RecordingSqlListener listener = new RecordingSqlListener();
        H2IntegrationTestSupport support = H2IntegrationTestSupport.createWithManagedTransactions(listener);
        SchemaInitializer schema =
                new SimpleSchemaInitializer(support.operations(), support.metadataFactory(), support.dialect());
        schema.create(Seat.class, SeatLine.class).block();

        // 첫 컴포넌트("A")를 공유하고 두 번째 컴포넌트(41 vs 42)만 다른 두 형제 행. 둘 다 version 0으로 시작한다.
        Seat target = new Seat(new SeatId("A", 41), "target");
        Seat sibling = new Seat(new SeatId("A", 42), "sibling");
        support.operations().save(target).block();
        support.operations().save(sibling).block();

        // target 을 갱신해 stored version 을 0→1 로 bump 한다. 이제 sibling(version 0)만이 아래 stale version 0 을
        // 만족한다 — 술어가 두 번째 컴포넌트를 빠뜨리면 sibling 을 오탐한다.
        Seat loaded = support.operations().findById(Seat.class, new SeatId("A", 41)).block();
        assertNotNull(loaded);
        loaded.setLabel("target-v1");
        support.operations().update(loaded, List.of("label")).block();

        // stale: 존재하지 않는 (A,41)@version 0 을 든 분리 인스턴스로 hard delete.
        Seat stale = new Seat(new SeatId("A", 41), "target");
        stale.setVersion(0L);

        listener.clear();
        StepVerifier.create(support.operations().delete(stale))
                .expectError(OptimisticLockingFailureException.class)
                .verify();

        // 선검증 COUNT 가 두 번째 id 컴포넌트(seat_no=41)를 바인딩으로 실제로 걸었는지 — fix 가 되돌려지면
        // (첫 컴포넌트만 걸면) 41 이 빠지고 sibling(version 0)에 매칭되어 이 assertion 과 위 error 타입이 함께 깨진다.
        SqlStatement count = listener.lastCount();
        assertNotNull(count, "hard delete는 owner 정리 앞에서 version 선검증 count(*)를 발행해야 한다");
        assertTrue(count.bindings().contains(41),
                "복합키 version 선검증 count는 두 번째 id 컴포넌트(seat_no=41)를 바인딩해야 한다: "
                        + count.sql() + " bindings=" + count.bindings());
        assertTrue(count.bindings().contains("A"),
                "복합키 version 선검증 count는 첫 id 컴포넌트(section=A)를 바인딩해야 한다: " + count.bindings());

        // 낙관락 선검증이 어떤 정리보다 먼저 멈췄으므로 두 형제 행 모두 물리 보존된다(원자적 무변경).
        assertEquals(2L, seatRowCount(support), "stale delete는 어떤 행도 지우지 않아야 한다");
    }

    @Test
    void idClassVersionGuardBindsEveryIdComponentAndRejectsStaleSibling() {
        RecordingSqlListener listener = new RecordingSqlListener();
        H2IntegrationTestSupport support = H2IntegrationTestSupport.createWithManagedTransactions(listener);
        SchemaInitializer schema =
                new SimpleSchemaInitializer(support.operations(), support.metadataFactory(), support.dialect());
        schema.create(IdClassSeat.class, IdClassSeatLine.class).block();

        IdClassSeat target = new IdClassSeat("A", 41, "target");
        IdClassSeat sibling = new IdClassSeat("A", 42, "sibling");
        support.operations().save(target).block();
        support.operations().save(sibling).block();

        IdClassSeat loaded = support.operations().findById(IdClassSeat.class, new SeatKey("A", 41)).block();
        assertNotNull(loaded);
        loaded.setLabel("target-v1");
        support.operations().update(loaded, List.of("label")).block();

        IdClassSeat stale = new IdClassSeat("A", 41, "target");
        stale.setVersion(0L);

        listener.clear();
        StepVerifier.create(support.operations().delete(stale))
                .expectError(OptimisticLockingFailureException.class)
                .verify();

        SqlStatement count = listener.lastCount();
        assertNotNull(count, "hard delete는 owner 정리 앞에서 version 선검증 count(*)를 발행해야 한다");
        assertTrue(count.bindings().contains(41),
                "@IdClass version 선검증 count는 두 번째 id 컴포넌트(seat_no=41)를 바인딩해야 한다: "
                        + count.sql() + " bindings=" + count.bindings());
        assertTrue(count.bindings().contains("A"),
                "@IdClass version 선검증 count는 첫 id 컴포넌트(section=A)를 바인딩해야 한다: " + count.bindings());

        assertEquals(2L, idClassSeatRowCount(support), "stale delete는 어떤 행도 지우지 않아야 한다");
    }

    private long seatRowCount(H2IntegrationTestSupport support) {
        return support.operations().queryNativeOne(
                NativeQuery.of("select count(*) as c from " + support.dialect().quote("ckv_seat")),
                row -> row.get("c", Long.class)).block();
    }

    private long idClassSeatRowCount(H2IntegrationTestSupport support) {
        return support.operations().queryNativeOne(
                NativeQuery.of("select count(*) as c from " + support.dialect().quote("ckv_idclass_seat")),
                row -> row.get("c", Long.class)).block();
    }

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
    }

    @Entity
    @Table(name = "ckv_seat")
    public static class Seat {
        @EmbeddedId
        private SeatId id;
        @Column(name = "label")
        private String label;
        @Version
        @Column(name = "ver")
        private Long version;
        @OneToMany(targetEntity = SeatLine.class, mappedBy = "seat",
                cascade = CascadeType.ALL, orphanRemoval = true)
        private List<SeatLine> lines = new ArrayList<>();

        public Seat() {
        }

        public Seat(SeatId id, String label) {
            this.id = id;
            this.label = label;
        }

        public void setLabel(String label) {
            this.label = label;
        }

        public void setVersion(Long version) {
            this.version = version;
        }
    }

    @Entity
    @Table(name = "ckv_seat_line")
    public static class SeatLine {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;
        @Column(name = "label")
        private String label;
        @ManyToOne(targetEntity = Seat.class)
        @JoinColumns({
                @JoinColumn(name = "seat_section", referencedColumnName = "section"),
                @JoinColumn(name = "seat_no_fk", referencedColumnName = "seat_no")
        })
        private Seat seat;

        public SeatLine() {
        }
    }

    public static class SeatKey implements Serializable {
        private String section;
        private Integer seatNo;

        public SeatKey() {
        }

        public SeatKey(String section, Integer seatNo) {
            this.section = section;
            this.seatNo = seatNo;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof SeatKey that)) {
                return false;
            }
            return Objects.equals(section, that.section) && Objects.equals(seatNo, that.seatNo);
        }

        @Override
        public int hashCode() {
            return Objects.hash(section, seatNo);
        }
    }

    @Entity
    @Table(name = "ckv_idclass_seat")
    @IdClass(SeatKey.class)
    public static class IdClassSeat {
        @Id
        @Column(name = "section")
        private String section;
        @Id
        @Column(name = "seat_no")
        private Integer seatNo;
        @Column(name = "label")
        private String label;
        @Version
        @Column(name = "ver")
        private Long version;
        @OneToMany(targetEntity = IdClassSeatLine.class, mappedBy = "seat",
                cascade = CascadeType.ALL, orphanRemoval = true)
        private List<IdClassSeatLine> lines = new ArrayList<>();

        public IdClassSeat() {
        }

        public IdClassSeat(String section, Integer seatNo, String label) {
            this.section = section;
            this.seatNo = seatNo;
            this.label = label;
        }

        public void setLabel(String label) {
            this.label = label;
        }

        public void setVersion(Long version) {
            this.version = version;
        }
    }

    @Entity
    @Table(name = "ckv_idclass_seat_line")
    public static class IdClassSeatLine {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;
        @Column(name = "label")
        private String label;
        @ManyToOne(targetEntity = IdClassSeat.class)
        @JoinColumns({
                @JoinColumn(name = "seat_section", referencedColumnName = "section"),
                @JoinColumn(name = "seat_no_fk", referencedColumnName = "seat_no")
        })
        private IdClassSeat seat;

        public IdClassSeatLine() {
        }
    }
}
