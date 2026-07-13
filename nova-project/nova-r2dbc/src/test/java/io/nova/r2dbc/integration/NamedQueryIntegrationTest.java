package io.nova.r2dbc.integration;

import io.nova.core.RowAccessor;
import io.nova.query.jpql.NamedQueryRegistry;
import io.nova.schema.SchemaInitializer;
import io.nova.schema.SimpleSchemaInitializer;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.NamedNativeQuery;
import jakarta.persistence.NamedQuery;
import jakarta.persistence.Table;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code @NamedQuery}/{@code @NamedNativeQuery} 레지스트리가 H2 in-memory R2DBC driver와 end-to-end로
 * 동작하는지 검증한다 — 명명 JPQL 실행(파싱→SQL→하이드레이션), 명명 네이티브 SELECT의 엔티티 매핑,
 * 명명 네이티브 벌크 UPDATE 라운드트립.
 */
class NamedQueryIntegrationTest {

    private H2IntegrationTestSupport support;
    private NamedQueryRegistry registry;

    @BeforeEach
    void setUp() {
        support = H2IntegrationTestSupport.create();
        SchemaInitializer schema =
                new SimpleSchemaInitializer(support.operations(), support.metadataFactory(), support.dialect());
        schema.create(Widget.class).block();
        registry = new NamedQueryRegistry(support.operations(), support.dialect(), support.metadataFactory(),
                Widget.class);

        support.operations().save(new Widget("Alpha", new BigDecimal("10"))).block();
        support.operations().save(new Widget("Beta", new BigDecimal("30"))).block();
        support.operations().save(new Widget("Gamma", new BigDecimal("20"))).block();
    }

    @Test
    void namedJpqlQueryExecutes() {
        StepVerifier.create(
                        registry.createQuery("Widget.byMinPrice", Widget.class)
                                .setParameter("min", new BigDecimal("20"))
                                .getResultList())
                .assertNext(w -> assertEquals("Beta", w.getName()))
                .assertNext(w -> assertEquals("Gamma", w.getName()))
                .verifyComplete();
    }

    @Test
    @SuppressWarnings("unchecked")
    void namedNativeQueryMapsToEntity() {
        StepVerifier.create(
                        ((io.nova.query.jpql.NamedNativeQuery<Widget>) registry.createNativeQuery("Widget.rawAll"))
                                .getResultList())
                .assertNext(w -> {
                    assertEquals("Alpha", w.getName());
                    assertEquals(0, w.getPrice().compareTo(new BigDecimal("10")));
                })
                .assertNext(w -> assertEquals("Beta", w.getName()))
                .assertNext(w -> assertEquals("Gamma", w.getName()))
                .verifyComplete();
    }

    @Test
    @SuppressWarnings("unchecked")
    void namedNativeQueryWithNamedParameterMapsToEntity() {
        StepVerifier.create(
                        ((io.nova.query.jpql.NamedNativeQuery<Widget>) registry.createNativeQuery("Widget.rawByName"))
                                .setParameter("name", "Beta")
                                .getSingleResult())
                .assertNext(w -> {
                    assertEquals("Beta", w.getName());
                    assertEquals(0, w.getPrice().compareTo(new BigDecimal("30")));
                })
                .verifyComplete();
    }

    @Test
    void namedNativeBulkUpdateExecutesAndPersists() {
        StepVerifier.create(
                        registry.createNativeQuery("Widget.bumpPrice")
                                .setParameter("delta", new BigDecimal("5"))
                                .setParameter("min", new BigDecimal("20"))
                                .executeUpdate())
                .assertNext(affected -> assertEquals(2L, affected))
                .verifyComplete();

        // Beta(30+5=35), Gamma(20+5=25) 인상, Alpha(10)는 유지.
        Long raised = registry.createNativeQuery("Widget.countAtLeast", (RowAccessor row) -> row.get("cnt", Long.class))
                .setParameter("min", new BigDecimal("25"))
                .getSingleResult()
                .block();
        assertEquals(2L, raised);
    }

    // ------------------------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------------------------

    @Entity
    @Table(name = "named_widget")
    @NamedQuery(name = "Widget.byMinPrice",
            query = "SELECT w FROM Widget w WHERE w.price >= :min ORDER BY w.name")
    @NamedNativeQuery(name = "Widget.rawAll",
            query = "SELECT * FROM \"named_widget\" ORDER BY \"name\"", resultClass = Widget.class)
    @NamedNativeQuery(name = "Widget.rawByName",
            query = "SELECT * FROM \"named_widget\" WHERE \"name\" = :name", resultClass = Widget.class)
    @NamedNativeQuery(name = "Widget.bumpPrice",
            query = "UPDATE \"named_widget\" SET \"price\" = \"price\" + :delta WHERE \"price\" >= :min")
    @NamedNativeQuery(name = "Widget.countAtLeast",
            query = "SELECT COUNT(*) AS \"cnt\" FROM \"named_widget\" WHERE \"price\" >= :min")
    public static class Widget {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        @Column(name = "id")
        private Long id;
        @Column(name = "name")
        private String name;
        @Column(name = "price")
        private BigDecimal price;

        public Widget() {
        }

        public Widget(String name, BigDecimal price) {
            this.name = name;
            this.price = price;
        }

        public Long getId() {
            return id;
        }

        public String getName() {
            return name;
        }

        public BigDecimal getPrice() {
            return price;
        }
    }
}
