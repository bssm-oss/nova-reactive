package io.nova.r2dbc.integration;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import io.nova.schema.SchemaInitializer;
import io.nova.schema.SimpleSchemaInitializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * to-one FK({@code @ManyToOne}) 컬럼 타입이 참조 대상의 단일 {@code @Id} 타입(UUID/String/Integer/Long)에
 * 정렬되는지를 실제 H2 in-memory R2DBC driver로 end-to-end 검증한다.
 *
 * <p>사전존재 결함: FK 프로퍼티 javaType이 항상 {@code Long}으로 하드코딩되어 (a) FK DDL이 항상 bigint라
 * 참조 PK(varchar/integer)와 타입 불일치 → FK 제약 DDL 거부, (b) UUID/String id를 bigint 컬럼에 바인딩 →
 * 드라이버 에러가 발생했다. 본 테스트는 production {@link SimpleSchemaInitializer}로 FK 제약까지 포함한 스키마를
 * 생성(=DDL 타입 정렬 검증)한 뒤, 참조 대상 row를 seed하고 {@code save(child)}로 FK 값을 저장타입으로 인코딩,
 * {@code findById(child)}로 연관을 하이드레이션해 FK 라운드트립을 확인한다.
 *
 * <p>참조 대상(UUID/String/Integer @Id)은 application-assigned 식별자라 {@code save()}가 UPDATE로 처리하므로
 * raw SQL로 seed한다(Long 대상만 {@code @GeneratedValue}로 save round-trip). child는 {@code @GeneratedValue}
 * Long @Id를 가지며 FK 인코딩(insert)/디코딩(read)이 이 테스트의 scope다.
 */
class ToOneForeignKeyTypeIntegrationTest {
    private H2IntegrationTestSupport support;

    @BeforeEach
    void setUp() {
        support = H2IntegrationTestSupport.create();
        SchemaInitializer schema =
                new SimpleSchemaInitializer(support.operations(), support.metadataFactory(), support.dialect());
        // create()는 테이블 + FK 제약(ALTER TABLE ADD FOREIGN KEY)을 발행한다. FK 컬럼 타입이 참조 PK와
        // 다르면(예: bigint FK vs varchar PK) H2가 제약 DDL을 거부하므로, 이 create() 성공 자체가 타입 정렬을 방증한다.
        schema.create(
                UuidParent.class, UuidChild.class,
                StringParent.class, StringChild.class,
                IntegerParent.class, IntegerChild.class,
                LongParent.class, LongChild.class).block();
    }

    @Test
    void manyToOneToUuidKeyedParentRoundTripsForeignKeyAsVarchar() {
        UUID parentId = UUID.randomUUID();
        support.execute("insert into \"fk_uuid_parent\" (\"id\", \"name\") values ('"
                + parentId + "', 'ada')");

        UuidParent parentRef = new UuidParent();
        parentRef.setId(parentId);
        UuidChild child = new UuidChild();
        child.setLabel("uuid-child");
        child.setParent(parentRef);

        StepVerifier.create(support.operations().save(child)
                        .flatMap(saved -> support.operations().findById(UuidChild.class, saved.getId())))
                .assertNext(loaded -> {
                    assertNotNull(loaded.getParent(), "@ManyToOne(UUID @Id) 연관이 하이드레이션돼야 한다");
                    assertEquals(parentId, loaded.getParent().getId(), "FK가 UUID로 라운드트립돼야 한다");
                    assertEquals("ada", loaded.getParent().getName(), "참조 대상이 완전 로드돼야 한다");
                })
                .verifyComplete();
    }

    @Test
    void manyToOneToStringKeyedParentRoundTripsForeignKeyAsVarchar() {
        support.execute("insert into \"fk_string_parent\" (\"code\", \"label\") values ('SKU-1', 'widget')");

        StringParent parentRef = new StringParent();
        parentRef.setCode("SKU-1");
        StringChild child = new StringChild();
        child.setName("string-child");
        child.setParent(parentRef);

        StepVerifier.create(support.operations().save(child)
                        .flatMap(saved -> support.operations().findById(StringChild.class, saved.getId())))
                .assertNext(loaded -> {
                    assertNotNull(loaded.getParent());
                    assertEquals("SKU-1", loaded.getParent().getCode());
                    assertEquals("widget", loaded.getParent().getLabel());
                })
                .verifyComplete();
    }

    @Test
    void manyToOneToIntegerKeyedParentRoundTripsForeignKeyAsInteger() {
        support.execute("insert into \"fk_integer_parent\" (\"id\", \"title\") values (42, 'sales')");

        IntegerParent parentRef = new IntegerParent();
        parentRef.setId(42);
        IntegerChild child = new IntegerChild();
        child.setName("integer-child");
        child.setParent(parentRef);

        StepVerifier.create(support.operations().save(child)
                        .flatMap(saved -> support.operations().findById(IntegerChild.class, saved.getId())))
                .assertNext(loaded -> {
                    assertNotNull(loaded.getParent());
                    assertEquals(42, loaded.getParent().getId());
                    assertEquals("sales", loaded.getParent().getTitle());
                })
                .verifyComplete();
    }

    @Test
    void manyToOneToLongKeyedParentHasNoRegression() {
        // 현행 다수 케이스: Long @Id 참조는 bigint FK로 그대로 동작한다. 참조 대상도 save round-trip한다.
        LongParent parent = new LongParent();
        parent.setName("legacy");

        StepVerifier.create(support.operations().save(parent)
                        .flatMap(savedParent -> {
                            LongChild child = new LongChild();
                            child.setName("long-child");
                            child.setParent(savedParent);
                            return support.operations().save(child);
                        })
                        .flatMap(savedChild -> support.operations().findById(LongChild.class, savedChild.getId())))
                .assertNext(loaded -> {
                    assertNotNull(loaded.getParent());
                    assertNotNull(loaded.getParent().getId());
                    assertEquals("legacy", loaded.getParent().getName());
                })
                .verifyComplete();
    }

    // --- UUID @Id ----------------------------------------------------------

    @Entity
    @Table(name = "fk_uuid_parent")
    public static class UuidParent {
        @Id
        @Column(name = "id")
        private UUID id;

        @Column(name = "name")
        private String name;

        public UuidParent() {
        }

        public UUID getId() {
            return id;
        }

        public void setId(UUID id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }
    }

    @Entity
    @Table(name = "fk_uuid_child")
    public static class UuidChild {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        @Column(name = "id")
        private Long id;

        @Column(name = "label")
        private String label;

        @ManyToOne(targetEntity = UuidParent.class)
        @JoinColumn(name = "parent_id")
        private UuidParent parent;

        public UuidChild() {
        }

        public Long getId() {
            return id;
        }

        public void setLabel(String label) {
            this.label = label;
        }

        public UuidParent getParent() {
            return parent;
        }

        public void setParent(UuidParent parent) {
            this.parent = parent;
        }
    }

    // --- String @Id --------------------------------------------------------

    @Entity
    @Table(name = "fk_string_parent")
    public static class StringParent {
        @Id
        @Column(name = "code")
        private String code;

        @Column(name = "label")
        private String label;

        public StringParent() {
        }

        public String getCode() {
            return code;
        }

        public void setCode(String code) {
            this.code = code;
        }

        public String getLabel() {
            return label;
        }
    }

    @Entity
    @Table(name = "fk_string_child")
    public static class StringChild {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        @Column(name = "id")
        private Long id;

        @Column(name = "name")
        private String name;

        @ManyToOne(targetEntity = StringParent.class)
        @JoinColumn(name = "parent_code")
        private StringParent parent;

        public StringChild() {
        }

        public Long getId() {
            return id;
        }

        public void setName(String name) {
            this.name = name;
        }

        public StringParent getParent() {
            return parent;
        }

        public void setParent(StringParent parent) {
            this.parent = parent;
        }
    }

    // --- Integer @Id -------------------------------------------------------

    @Entity
    @Table(name = "fk_integer_parent")
    public static class IntegerParent {
        @Id
        @Column(name = "id")
        private Integer id;

        @Column(name = "title")
        private String title;

        public IntegerParent() {
        }

        public Integer getId() {
            return id;
        }

        public void setId(Integer id) {
            this.id = id;
        }

        public String getTitle() {
            return title;
        }
    }

    @Entity
    @Table(name = "fk_integer_child")
    public static class IntegerChild {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        @Column(name = "id")
        private Long id;

        @Column(name = "name")
        private String name;

        @ManyToOne(targetEntity = IntegerParent.class)
        @JoinColumn(name = "parent_id")
        private IntegerParent parent;

        public IntegerChild() {
        }

        public Long getId() {
            return id;
        }

        public void setName(String name) {
            this.name = name;
        }

        public IntegerParent getParent() {
            return parent;
        }

        public void setParent(IntegerParent parent) {
            this.parent = parent;
        }
    }

    // --- Long @Id (regression baseline) ------------------------------------

    @Entity
    @Table(name = "fk_long_parent")
    public static class LongParent {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        @Column(name = "id")
        private Long id;

        @Column(name = "name")
        private String name;

        public LongParent() {
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

    @Entity
    @Table(name = "fk_long_child")
    public static class LongChild {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        @Column(name = "id")
        private Long id;

        @Column(name = "name")
        private String name;

        @ManyToOne(targetEntity = LongParent.class)
        @JoinColumn(name = "parent_id")
        private LongParent parent;

        public LongChild() {
        }

        public Long getId() {
            return id;
        }

        public void setName(String name) {
            this.name = name;
        }

        public LongParent getParent() {
            return parent;
        }

        public void setParent(LongParent parent) {
            this.parent = parent;
        }
    }
}
