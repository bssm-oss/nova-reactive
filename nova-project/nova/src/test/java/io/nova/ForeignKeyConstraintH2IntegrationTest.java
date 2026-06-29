package io.nova;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ConstraintMode;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
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

    @Test
    void repeatedCreateWithIfNotExistsIsIdempotentForForeignKeys() {
        ConnectionFactory cf = freshConnectionFactory();
        SchemaInitializer schema = Nova.schemaInitializer(cf);
        ReactiveEntityOperations operations = Nova.create(cf);

        // ddl-auto=UPDATE 재시작 시뮬레이션: 같은 DB에 create()(ifNotExists=true 기본)를 두 번 호출. FK ALTER가
        // 중복 발행되면(이전 버그) 두 번째 create가 "constraint already exists"로 깨진다 → 멱등이어야 완료된다.
        StepVerifier.create(
                schema.create(java.util.List.of(FkParent.class, FkChildConstrained.class))
                        .then(schema.create(java.util.List.of(FkParent.class, FkChildConstrained.class)))
        ).verifyComplete();

        // FK 제약은 정확히 한 번 존재 → 위반 INSERT는 여전히 거부된다(중복도 누락도 아님).
        StepVerifier.create(
                operations.executeNative(NativeQuery.of(
                        "insert into \"fk_child_constrained\" (\"id\", \"parent_id\") values (1, 999)"))
        ).verifyError();
    }

    @Test
    void joinTableForeignKeysRejectViolatingLinkInsert() {
        ConnectionFactory cf = freshConnectionFactory();
        SchemaInitializer schema = Nova.schemaInitializer(cf);
        ReactiveEntityOperations operations = Nova.create(cf);

        // owning @ManyToMany의 @JoinTable(foreignKey=, inverseForeignKey=)로 link table 양쪽 FK가 발행된다.
        // 존재하지 않는 student/course를 가리키는 link row INSERT는 FK 위반으로 driver가 거부해야 한다.
        StepVerifier.create(
                schema.create(java.util.List.of(FkStudent.class, FkCourse.class))
                        .then(operations.executeNative(NativeQuery.of(
                                "insert into \"fk_enrollment\" (\"student_id\", \"course_id\") values (999, 888)")))
        ).verifyError();
    }

    @Test
    void joinTableForeignKeysAllowValidLinkInsert() {
        ConnectionFactory cf = freshConnectionFactory();
        SchemaInitializer schema = Nova.schemaInitializer(cf);
        ReactiveEntityOperations operations = Nova.create(cf);

        // 양쪽 부모 행이 먼저 존재하면 link row는 FK를 통과해야 한다(제약이 과도하게 막지 않는지 확인).
        StepVerifier.create(
                schema.create(java.util.List.of(FkStudent.class, FkCourse.class))
                        .then(operations.executeNative(NativeQuery.of(
                                "insert into \"fk_student\" (\"id\") values (1)")))
                        .then(operations.executeNative(NativeQuery.of(
                                "insert into \"fk_course\" (\"id\") values (2)")))
                        .then(operations.executeNative(NativeQuery.of(
                                "insert into \"fk_enrollment\" (\"student_id\", \"course_id\") values (1, 2)")))
        ).expectNextCount(1).verifyComplete();
    }

    @Test
    void collectionTableForeignKeyRejectsViolatingInsert() {
        ConnectionFactory cf = freshConnectionFactory();
        SchemaInitializer schema = Nova.schemaInitializer(cf);
        ReactiveEntityOperations operations = Nova.create(cf);

        // @ElementCollection의 @CollectionTable(foreignKey=...)로 owner FK가 발행된다. 존재하지 않는 owner를
        // 가리키는 collection row INSERT는 FK 위반으로 거부되어야 한다.
        StepVerifier.create(
                schema.create(java.util.List.of(FkElementOwner.class))
                        .then(operations.executeNative(NativeQuery.of(
                                "insert into \"fk_owner_tags\" (\"owner_id\", \"tag\") values (999, 'x')")))
        ).verifyError();
    }

    @Test
    void unnamedForeignKeyWithLongIdentifiersIsBoundedAndEnforcedIdempotently() {
        ConnectionFactory cf = freshConnectionFactory();
        SchemaInitializer schema = Nova.schemaInitializer(cf);
        ReactiveEntityOperations operations = Nova.create(cf);

        // 긴 테이블/컬럼명 + 이름 없는 @ForeignKey(기본 CONSTRAINT) → 자동 제약명이 한계 내로 bound된다.
        // (1) 멱등 발행(ifNotExists 기본)을 두 번 호출해도 깨지지 않고, (2) bound된 FK가 위반 INSERT를 거부한다.
        StepVerifier.create(
                schema.create(java.util.List.of(LongNameFkParent.class, LongNameFkChild.class))
                        .then(schema.create(java.util.List.of(LongNameFkParent.class, LongNameFkChild.class)))
        ).verifyComplete();

        StepVerifier.create(
                operations.executeNative(NativeQuery.of(
                        "insert into \"warehouse_inventory_reconciliation_audit_history_child\""
                                + " (\"id\", \"originating_distribution_center_reference_identifier\")"
                                + " values (1, 999)"))
        ).verifyError();
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
    @Table(name = "fk_student")
    public static class FkStudent {
        @Id
        private Long id;

        @ManyToMany(targetEntity = FkCourse.class)
        @JoinTable(
                name = "fk_enrollment",
                joinColumns = @JoinColumn(name = "student_id"),
                inverseJoinColumns = @JoinColumn(name = "course_id"),
                foreignKey = @ForeignKey(name = "fk_enr_student"),
                inverseForeignKey = @ForeignKey(name = "fk_enr_course"))
        private java.util.Set<FkCourse> courses;

        public FkStudent() {
        }

        public Long getId() {
            return id;
        }
    }

    @Entity
    @Table(name = "fk_course")
    public static class FkCourse {
        @Id
        private Long id;

        public FkCourse() {
        }

        public Long getId() {
            return id;
        }
    }

    @Entity
    @Table(name = "fk_element_owner")
    public static class FkElementOwner {
        @Id
        private Long id;

        @ElementCollection
        @CollectionTable(
                name = "fk_owner_tags",
                joinColumns = @JoinColumn(name = "owner_id"),
                foreignKey = @ForeignKey(name = "fk_tags_owner"))
        @Column(name = "tag")
        private java.util.Set<String> tags;

        public FkElementOwner() {
        }

        public Long getId() {
            return id;
        }
    }

    @Entity
    @Table(name = "warehouse_inventory_reconciliation_audit_history_parent")
    public static class LongNameFkParent {
        @Id
        private Long id;

        public LongNameFkParent() {
        }

        public Long getId() {
            return id;
        }
    }

    @Entity
    @Table(name = "warehouse_inventory_reconciliation_audit_history_child")
    public static class LongNameFkChild {
        @Id
        private Long id;

        // 이름 없는 @ForeignKey(기본 CONSTRAINT) → fk_<table>_<column> 자동 이름이 63자를 넘어 bound된다.
        @ManyToOne(targetEntity = LongNameFkParent.class)
        @JoinColumn(name = "originating_distribution_center_reference_identifier",
                foreignKey = @ForeignKey)
        private LongNameFkParent parent;

        public LongNameFkChild() {
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
