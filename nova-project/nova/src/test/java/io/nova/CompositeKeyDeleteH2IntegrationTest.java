package io.nova;

import io.nova.annotation.SoftDelete;
import io.nova.core.ReactiveEntityOperations;
import io.nova.exception.OptimisticLockingFailureException;
import io.nova.query.NativeQuery;
import io.nova.schema.SchemaInitializer;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 복합키(@EmbeddedId / @IdClass) delete 경로를 실제 R2DBC H2 driver로 검증한다. SQL string 단위 테스트만으로는
 * 다중 컬럼 WHERE 절이 driver에 수용되는지, soft-delete UPDATE가 논리 보존을 지키는지 알 수 없다 —
 * ddl-auto 라운드트립으로 (1) @SoftDelete + deleteAllById가 복합키에서 per-id UPDATE로 폴백해 rejectCompositeId로
 * 깨지지 않는지, (2) 복합키 hard deleteAllById가 per-id 물리 삭제로 폴백하는지, (3) versioned 복합키 soft-delete가
 * stale 버전에서 원자적으로 거부(부분 커밋 없음)되는지를 확인한다. 데이터베이스는 메모리 H2(독립 DB per test).
 */
class CompositeKeyDeleteH2IntegrationTest {

    private static final AtomicInteger DB_SEQ = new AtomicInteger();

    private ConnectionFactory freshConnectionFactory() {
        int seq = DB_SEQ.incrementAndGet();
        return ConnectionFactories.get(
                "r2dbc:h2:mem:///compositekeydelete" + seq + "?options=DB_CLOSE_DELAY=-1");
    }

    private Mono<Long> countRows(ReactiveEntityOperations operations, String table) {
        return operations.queryNativeOne(
                NativeQuery.of("select count(*) as c from \"" + table + "\""),
                row -> row.get("c", Long.class));
    }

    private Mono<Long> countAlive(ReactiveEntityOperations operations, String table) {
        return operations.queryNativeOne(
                NativeQuery.of("select count(*) as c from \"" + table + "\" where \"deleted_at\" is null"),
                row -> row.get("c", Long.class));
    }

    @Test
    void embeddedIdSoftDeleteViaDeleteAllByIdFallsBackToPerIdUpdate() {
        ConnectionFactory cf = freshConnectionFactory();
        SchemaInitializer schema = Nova.schemaInitializer(cf);
        ReactiveEntityOperations operations = Nova.create(cf);

        SoftLine a = new SoftLine(new OrderKey(1L, 1), "a");
        SoftLine b = new SoftLine(new OrderKey(1L, 2), "b");
        SoftLine c = new SoftLine(new OrderKey(2L, 1), "c");

        // (1,1),(1,2)를 soft-delete → 물리 행은 3건 그대로, deleted_at 채워진 행 2건, 살아있는 행 1건.
        StepVerifier.create(
                schema.create(List.of(SoftLine.class))
                        .then(operations.save(a))
                        .then(operations.save(b))
                        .then(operations.save(c))
                        .then(operations.deleteAllById(
                                SoftLine.class, List.of(new OrderKey(1L, 1), new OrderKey(1L, 2))))
        ).expectNext(2L).verifyComplete();

        // soft-delete 된 복합키는 findById 에서 alive 가드에 걸려 조회되지 않는다.
        StepVerifier.create(operations.findById(SoftLine.class, new OrderKey(1L, 1)))
                .verifyComplete();
        // 지우지 않은 복합키는 그대로 조회된다.
        StepVerifier.create(operations.findById(SoftLine.class, new OrderKey(2L, 1)))
                .expectNextCount(1).verifyComplete();
        // 물리 행은 보존(3건), alive 는 1건.
        StepVerifier.create(countRows(operations, "ck_soft_line")).expectNext(3L).verifyComplete();
        StepVerifier.create(countAlive(operations, "ck_soft_line")).expectNext(1L).verifyComplete();
    }

    @Test
    void idClassSoftDeleteViaDeleteAllByIdFallsBackToPerIdUpdate() {
        ConnectionFactory cf = freshConnectionFactory();
        SchemaInitializer schema = Nova.schemaInitializer(cf);
        ReactiveEntityOperations operations = Nova.create(cf);

        IdClassLine a = new IdClassLine(1L, 1, "a");
        IdClassLine b = new IdClassLine(1L, 2, "b");
        IdClassLine c = new IdClassLine(2L, 1, "c");

        StepVerifier.create(
                schema.create(List.of(IdClassLine.class))
                        .then(operations.save(a))
                        .then(operations.save(b))
                        .then(operations.save(c))
                        .then(operations.deleteAllById(
                                IdClassLine.class, List.of(new OrderIdClass(1L, 1), new OrderIdClass(1L, 2))))
        ).expectNext(2L).verifyComplete();

        StepVerifier.create(operations.findById(IdClassLine.class, new OrderIdClass(1L, 2)))
                .verifyComplete();
        StepVerifier.create(operations.findById(IdClassLine.class, new OrderIdClass(2L, 1)))
                .expectNextCount(1).verifyComplete();
        StepVerifier.create(countRows(operations, "ck_idclass_line")).expectNext(3L).verifyComplete();
        StepVerifier.create(countAlive(operations, "ck_idclass_line")).expectNext(1L).verifyComplete();
    }

    @Test
    void embeddedIdHardDeleteViaDeleteAllByIdFallsBackToPerIdDelete() {
        ConnectionFactory cf = freshConnectionFactory();
        SchemaInitializer schema = Nova.schemaInitializer(cf);
        ReactiveEntityOperations operations = Nova.create(cf);

        PlainLine a = new PlainLine(new OrderKey(1L, 1), "a");
        PlainLine b = new PlainLine(new OrderKey(1L, 2), "b");
        PlainLine c = new PlainLine(new OrderKey(2L, 1), "c");

        // @SoftDelete 없음 → per-id 물리 삭제로 폴백. 남는 물리 행은 1건.
        StepVerifier.create(
                schema.create(List.of(PlainLine.class))
                        .then(operations.save(a))
                        .then(operations.save(b))
                        .then(operations.save(c))
                        .then(operations.deleteAllById(
                                PlainLine.class, List.of(new OrderKey(1L, 1), new OrderKey(1L, 2))))
        ).expectNext(2L).verifyComplete();

        StepVerifier.create(operations.findById(PlainLine.class, new OrderKey(1L, 1)))
                .verifyComplete();
        StepVerifier.create(operations.findById(PlainLine.class, new OrderKey(2L, 1)))
                .expectNextCount(1).verifyComplete();
        StepVerifier.create(operations.queryNativeOne(
                NativeQuery.of("select count(*) as c from \"ck_plain_line\""),
                row -> row.get("c", Long.class))).expectNext(1L).verifyComplete();
    }

    @Test
    void versionedCompositeSoftDeleteSucceedsWithCurrentVersion() {
        ConnectionFactory cf = freshConnectionFactory();
        SchemaInitializer schema = Nova.schemaInitializer(cf);
        ReactiveEntityOperations operations = Nova.create(cf);

        VersionedLine v = new VersionedLine(new OrderKey(7L, 1));

        // 현재 버전을 가진 엔티티로 soft-delete 하면 성공하고 findById 에서 사라진다.
        StepVerifier.create(
                schema.create(List.of(VersionedLine.class))
                        .then(operations.save(v))
                        .flatMap(saved -> operations.delete(saved))
        ).expectNext(1L).verifyComplete();

        StepVerifier.create(operations.findById(VersionedLine.class, new OrderKey(7L, 1)))
                .verifyComplete();
        // 물리 행은 보존, alive 0건.
        StepVerifier.create(countRows(operations, "ck_ver_line")).expectNext(1L).verifyComplete();
        StepVerifier.create(countAlive(operations, "ck_ver_line")).expectNext(0L).verifyComplete();
    }

    @Test
    void versionedCompositeSoftDeleteRejectsStaleVersionAtomically() {
        ConnectionFactory cf = freshConnectionFactory();
        SchemaInitializer schema = Nova.schemaInitializer(cf);
        ReactiveEntityOperations operations = Nova.create(cf);

        VersionedLine v = new VersionedLine(new OrderKey(9L, 1));
        // DB 에 저장되지 않을 stale 버전을 든 detached 인스턴스.
        VersionedLine stale = new VersionedLine(new OrderKey(9L, 1));
        stale.version = 999_999L;

        // stale 버전 soft-delete 는 version AND 절에 걸려 0행 → OptimisticLockingFailureException.
        StepVerifier.create(
                schema.create(List.of(VersionedLine.class))
                        .then(operations.save(v))
                        .then(operations.delete(stale))
        ).verifyError(OptimisticLockingFailureException.class);

        // 단일 UPDATE 문이므로 부분 커밋이 불가능 — 행은 여전히 살아있고 deleted_at 은 null 이다.
        StepVerifier.create(operations.findById(VersionedLine.class, new OrderKey(9L, 1)))
                .expectNextCount(1).verifyComplete();
        StepVerifier.create(countAlive(operations, "ck_ver_line")).expectNext(1L).verifyComplete();
    }

    @Embeddable
    public static class OrderKey {
        @Column(name = "order_id")
        private Long orderId;
        @Column(name = "line_no")
        private Integer lineNo;

        public OrderKey() {
        }

        public OrderKey(Long orderId, Integer lineNo) {
            this.orderId = orderId;
            this.lineNo = lineNo;
        }
    }

    @Entity
    @Table(name = "ck_soft_line")
    public static class SoftLine {
        @EmbeddedId
        private OrderKey id;
        @Column(name = "descr")
        private String description;
        @SoftDelete
        @Column(name = "deleted_at")
        private LocalDateTime deletedAt;

        public SoftLine() {
        }

        public SoftLine(OrderKey id, String description) {
            this.id = id;
            this.description = description;
        }

        public OrderKey getId() {
            return id;
        }
    }

    @Entity
    @Table(name = "ck_plain_line")
    public static class PlainLine {
        @EmbeddedId
        private OrderKey id;
        @Column(name = "descr")
        private String description;

        public PlainLine() {
        }

        public PlainLine(OrderKey id, String description) {
            this.id = id;
            this.description = description;
        }
    }

    @Entity
    @Table(name = "ck_ver_line")
    public static class VersionedLine {
        @EmbeddedId
        private OrderKey id;
        @Version
        @Column(name = "ver")
        private Long version;
        @SoftDelete
        @Column(name = "deleted_at")
        private LocalDateTime deletedAt;

        public VersionedLine() {
        }

        public VersionedLine(OrderKey id) {
            this.id = id;
        }
    }

    public static class OrderIdClass implements Serializable {
        private Long orderId;
        private Integer lineNo;

        public OrderIdClass() {
        }

        public OrderIdClass(Long orderId, Integer lineNo) {
            this.orderId = orderId;
            this.lineNo = lineNo;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof OrderIdClass that)) {
                return false;
            }
            return Objects.equals(orderId, that.orderId) && Objects.equals(lineNo, that.lineNo);
        }

        @Override
        public int hashCode() {
            return Objects.hash(orderId, lineNo);
        }
    }

    @Entity
    @Table(name = "ck_idclass_line")
    @IdClass(OrderIdClass.class)
    public static class IdClassLine {
        @Id
        @Column(name = "order_id")
        private Long orderId;
        @Id
        @Column(name = "line_no")
        private Integer lineNo;
        @Column(name = "descr")
        private String description;
        @SoftDelete
        @Column(name = "deleted_at")
        private LocalDateTime deletedAt;

        public IdClassLine() {
        }

        public IdClassLine(Long orderId, Integer lineNo, String description) {
            this.orderId = orderId;
            this.lineNo = lineNo;
            this.description = description;
        }
    }
}
