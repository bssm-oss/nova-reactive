package io.nova;

import jakarta.persistence.ConstraintMode;
import jakarta.persistence.Entity;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import io.nova.core.ReactiveEntityOperations;
import io.nova.query.NativeQuery;
import io.nova.schema.SchemaInitializer;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@code @ForeignKey} 소스 호환을 실제 R2DBC H2 driver로 검증하는 통합 테스트. SQL string 단위 테스트만으로는
 * FK 제약이 driver에 의해 실제로 생성/강제되는지 알 수 없다 — ddl-auto 라운드트립으로 (1) CONSTRAINT 모드가
 * 위반 INSERT를 거부하는지, (2) NO_CONSTRAINT 모드가 같은 위반을 허용하는지, (3) @ForeignKey 부재(기존 동작)가
 * FK를 만들지 않는지를 확인한다. 데이터베이스는 메모리 H2(독립 DB per test).
 */
class ForeignKeyConstraintH2IntegrationTest {

    private static final AtomicInteger DB_SEQ = new AtomicInteger();

    private ConnectionFactory freshConnectionFactory() {
        int seq = DB_SEQ.incrementAndGet();
        return ConnectionFactories.get(
                "r2dbc:h2:mem:///fkconstraint" + seq + "?options=DB_CLOSE_DELAY=-1");
    }

    @Test
    void constraintModeForeignKeyRejectsViolatingInsert() {
        ConnectionFactory cf = freshConnectionFactory();
        SchemaInitializer schema = Nova.schemaInitializer(cf);
        ReactiveEntityOperations operations = Nova.create(cf);

        // parent 부터 child 순으로 생성(FK는 모든 테이블 생성 후 ALTER로 추가됨) → 존재하지 않는 parent_id=999로
        // child INSERT를 시도하면 FK 제약 위반으로 driver가 거부해야 한다.
        StepVerifier.create(
                schema.create(java.util.List.of(FkParent.class, FkChildConstrained.class))
                        .then(operations.executeNative(NativeQuery.of(
                                "insert into \"fk_child_constrained\" (\"id\", \"parent_id\") values (1, 999)")))
        ).verifyError();
    }

    @Test
    void constraintModeForeignKeyAllowsValidInsert() {
        ConnectionFactory cf = freshConnectionFactory();
        SchemaInitializer schema = Nova.schemaInitializer(cf);
        ReactiveEntityOperations operations = Nova.create(cf);

        // 존재하는 parent를 먼저 넣고 그 id를 참조하는 child는 FK 제약을 통과해야 한다.
        StepVerifier.create(
                schema.create(java.util.List.of(FkParent.class, FkChildConstrained.class))
                        .then(operations.executeNative(NativeQuery.of(
                                "insert into \"fk_parent\" (\"id\", \"name\") values (10, 'root')")))
                        .then(operations.executeNative(NativeQuery.of(
                                "insert into \"fk_child_constrained\" (\"id\", \"parent_id\") values (1, 10)")))
        ).expectNextCount(1).verifyComplete();
    }

    @Test
    void noConstraintModeAllowsViolatingInsert() {
        ConnectionFactory cf = freshConnectionFactory();
        SchemaInitializer schema = Nova.schemaInitializer(cf);
        ReactiveEntityOperations operations = Nova.create(cf);

        // @ForeignKey(NO_CONSTRAINT): FK 제약을 만들지 않으므로 존재하지 않는 parent를 가리키는 INSERT가 허용된다.
        StepVerifier.create(
                schema.create(java.util.List.of(FkParent.class, FkChildSuppressed.class))
                        .then(operations.executeNative(NativeQuery.of(
                                "insert into \"fk_child_suppressed\" (\"id\", \"parent_id\") values (1, 999)")))
        ).expectNextCount(1).verifyComplete();
    }

    @Test
    void providerDefaultWithoutForeignKeyAllowsViolatingInsert() {
        ConnectionFactory cf = freshConnectionFactory();
        SchemaInitializer schema = Nova.schemaInitializer(cf);
        ReactiveEntityOperations operations = Nova.create(cf);

        // @ForeignKey 부재(PROVIDER_DEFAULT) → Nova 기존 동작(FK 미발행) → 위반 INSERT 허용.
        StepVerifier.create(
                schema.create(java.util.List.of(FkParent.class, FkChildDefault.class))
                        .then(operations.executeNative(NativeQuery.of(
                                "insert into \"fk_child_default\" (\"id\", \"parent_id\") values (1, 999)")))
        ).expectNextCount(1).verifyComplete();
    }

    @Entity
    @Table(name = "fk_parent")
    public static class FkParent {
        @Id
        private Long id;
        private String name;

        public FkParent() {
        }

        public Long getId() {
            return id;
        }
    }

    @Entity
    @Table(name = "fk_child_constrained")
    public static class FkChildConstrained {
        @Id
        private Long id;

        @ManyToOne(targetEntity = FkParent.class)
        @JoinColumn(name = "parent_id", foreignKey = @ForeignKey(name = "fk_child_parent"))
        private FkParent parent;

        public FkChildConstrained() {
        }

        public Long getId() {
            return id;
        }
    }

    @Entity
    @Table(name = "fk_child_suppressed")
    public static class FkChildSuppressed {
        @Id
        private Long id;

        @ManyToOne(targetEntity = FkParent.class)
        @JoinColumn(name = "parent_id", foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
        private FkParent parent;

        public FkChildSuppressed() {
        }

        public Long getId() {
            return id;
        }
    }

    @Entity
    @Table(name = "fk_child_default")
    public static class FkChildDefault {
        @Id
        private Long id;

        @ManyToOne(targetEntity = FkParent.class)
        @JoinColumn(name = "parent_id")
        private FkParent parent;

        public FkChildDefault() {
        }

        public Long getId() {
            return id;
        }
    }
}
