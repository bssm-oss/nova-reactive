package io.nova.r2dbc.integration;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import io.nova.query.Criteria;
import io.nova.query.QuerySpec;
import io.nova.schema.SchemaInitializer;
import io.nova.schema.SimpleSchemaInitializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * {@code @OneToMany(cascade=...)} / {@code orphanRemoval=true}가 H2 in-memory R2DBC driver와 end-to-end로
 * 동작하는지 검증한다 — (a) cascade PERSIST가 parent save 시 child를 INSERT하며 FK를 parent id로 바인딩하는지,
 * (b) cascade REMOVE가 parent delete 시 child를 DELETE하는지, (c) orphanRemoval이 컬렉션에서 빠진 child를
 * 다음 save에서 DELETE하는지, (d) cascade/orphanRemoval 없는 marker-only @OneToMany는 child를 자동 저장하지
 * 않는(회귀) 것을 보장한다.
 *
 * <p>cycle 메모리(feedback_integration_test_surfaces_bugs.md)에 따라 SQL string unit test만으로는 reactive
 * cascade 순서/FK 바인딩/row decoding 호환을 보장할 수 없으므로 in-memory driver integration test로 보호한다.
 */
class OneToManyCascadeIntegrationTest {
    private H2IntegrationTestSupport support;

    @BeforeEach
    void setUp() {
        support = H2IntegrationTestSupport.create();
        SchemaInitializer schema =
                new SimpleSchemaInitializer(support.operations(), support.metadataFactory(), support.dialect());
        schema.create(CascadeParent.class, CascadeChild.class, MarkerParent.class, MarkerChild.class).block();
    }

    @Test
    void cascadePersistInsertsChildrenAndBindsForeignKey() {
        CascadeParent parent = new CascadeParent("p");
        parent.getChildren().add(new CascadeChild("a"));
        parent.getChildren().add(new CascadeChild("b"));

        Long parentId = support.operations().save(parent).map(CascadeParent::getId).block();
        assertNotNull(parentId);

        // child가 자동 INSERT되고 author_id FK가 parent id로 바인딩되어야 한다.
        List<CascadeChild> children = new ArrayList<>();
        StepVerifier.create(support.operations().findAll(
                        CascadeChild.class, QuerySpec.empty().where(Criteria.eq("parent", parentId))))
                .recordWith(() -> children)
                .expectNextCount(2)
                .verifyComplete();
        assertEquals(2, children.size());
        assertEquals(
                java.util.Set.of("a", "b"),
                children.stream().map(CascadeChild::getLabel).collect(Collectors.toSet()));
    }

    @Test
    void cascadeRemoveDeletesChildrenOnParentDelete() {
        CascadeParent parent = new CascadeParent("p");
        parent.getChildren().add(new CascadeChild("a"));
        parent.getChildren().add(new CascadeChild("b"));
        CascadeParent saved = support.operations().save(parent).block();

        // parent delete → child 2건이 함께 삭제되어야 한다.
        StepVerifier.create(support.operations().delete(saved))
                .expectNextCount(1)
                .verifyComplete();

        StepVerifier.create(support.operations().count(CascadeChild.class, QuerySpec.empty()))
                .assertNext(count -> assertEquals(0L, count))
                .verifyComplete();
    }

    @Test
    void orphanRemovalDeletesChildDroppedFromCollection() {
        CascadeParent parent = new CascadeParent("p");
        CascadeChild keep = new CascadeChild("keep");
        CascadeChild drop = new CascadeChild("drop");
        parent.getChildren().add(keep);
        parent.getChildren().add(drop);
        CascadeParent saved = support.operations().save(parent).block();

        StepVerifier.create(support.operations().count(CascadeChild.class, QuerySpec.empty()))
                .assertNext(count -> assertEquals(2L, count))
                .verifyComplete();

        // 컬렉션에서 drop을 제거하고 재저장 → orphan으로 삭제되어야 하고 keep만 남는다.
        saved.getChildren().removeIf(child -> "drop".equals(child.getLabel()));
        support.operations().save(saved).block();

        List<CascadeChild> remaining = new ArrayList<>();
        StepVerifier.create(support.operations().findAll(CascadeChild.class, QuerySpec.empty()))
                .recordWith(() -> remaining)
                .expectNextCount(1)
                .verifyComplete();
        assertEquals(1, remaining.size());
        assertEquals("keep", remaining.get(0).getLabel());
    }

    @Test
    void markerOnlyOneToManyDoesNotCascadePersist() {
        MarkerParent parent = new MarkerParent("p");
        parent.getChildren().add(new MarkerChild("orphaned"));

        // cascade/orphanRemoval 없는 @OneToMany는 child를 자동 저장하지 않는다(marker-only 회귀 가드).
        support.operations().save(parent).block();

        StepVerifier.create(support.operations().count(MarkerChild.class, QuerySpec.empty()))
                .assertNext(count -> assertEquals(0L, count, "marker-only @OneToMany는 child를 INSERT하지 않아야 한다"))
                .verifyComplete();
    }

    @Entity
    @Table(name = "cascade_parent")
    public static class CascadeParent {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;
        private String name;

        @OneToMany(targetEntity = CascadeChild.class, mappedBy = "parent",
                cascade = CascadeType.ALL, orphanRemoval = true)
        private List<CascadeChild> children = new ArrayList<>();

        public CascadeParent() {
        }

        public CascadeParent(String name) {
            this.name = name;
        }

        public Long getId() {
            return id;
        }

        public List<CascadeChild> getChildren() {
            return children;
        }
    }

    @Entity
    @Table(name = "cascade_child")
    public static class CascadeChild {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;
        private String label;

        @ManyToOne(targetEntity = CascadeParent.class)
        @JoinColumn(name = "parent_id")
        private CascadeParent parent;

        public CascadeChild() {
        }

        public CascadeChild(String label) {
            this.label = label;
        }

        public Long getId() {
            return id;
        }

        public String getLabel() {
            return label;
        }

        public CascadeParent getParent() {
            return parent;
        }
    }

    @Entity
    @Table(name = "marker_parent")
    public static class MarkerParent {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;
        private String name;

        // cascade/orphanRemoval 없는 marker-only @OneToMany — child 전파가 일어나면 안 된다.
        @OneToMany(targetEntity = MarkerChild.class, mappedBy = "parent")
        private List<MarkerChild> children = new ArrayList<>();

        public MarkerParent() {
        }

        public MarkerParent(String name) {
            this.name = name;
        }

        public Long getId() {
            return id;
        }

        public List<MarkerChild> getChildren() {
            return children;
        }
    }

    @Entity
    @Table(name = "marker_child")
    public static class MarkerChild {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;
        private String label;

        @ManyToOne(targetEntity = MarkerParent.class)
        @JoinColumn(name = "parent_id")
        private MarkerParent parent;

        public MarkerChild() {
        }

        public MarkerChild(String label) {
            this.label = label;
        }

        public Long getId() {
            return id;
        }

        public String getLabel() {
            return label;
        }

        public MarkerParent getParent() {
            return parent;
        }
    }
}
