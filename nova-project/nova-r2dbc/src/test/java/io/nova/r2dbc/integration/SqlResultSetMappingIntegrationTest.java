package io.nova.r2dbc.integration;

import io.nova.query.NativeQuery;
import io.nova.query.jpql.NamedQueryRegistry;
import io.nova.query.resultset.SqlResultSetMappingRegistry;
import io.nova.schema.SchemaInitializer;
import io.nova.schema.SimpleSchemaInitializer;
import jakarta.persistence.Column;
import jakarta.persistence.ColumnResult;
import jakarta.persistence.ConstructorResult;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityResult;
import jakarta.persistence.FieldResult;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.NamedNativeQuery;
import jakarta.persistence.SqlResultSetMapping;
import jakarta.persistence.Table;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code @SqlResultSetMapping}(native 결과 매핑)이 H2 in-memory R2DBC driver와 end-to-end로 동작하는지 검증한다 —
 * {@code @EntityResult} 엔티티 매핑({@code @FieldResult} 별칭 포함), {@code @ConstructorResult} DTO 매핑,
 * {@code @ColumnResult} 스칼라 매핑, 그리고 {@code @NamedNativeQuery(resultSetMapping=)} 라운드트립.
 */
class SqlResultSetMappingIntegrationTest {

    private H2IntegrationTestSupport support;
    private SqlResultSetMappingRegistry mappings;
    private NamedQueryRegistry namedQueries;

    @BeforeEach
    void setUp() {
        support = H2IntegrationTestSupport.create();
        SchemaInitializer schema =
                new SimpleSchemaInitializer(support.operations(), support.metadataFactory(), support.dialect());
        schema.create(Widget.class).block();
        mappings = new SqlResultSetMappingRegistry(support.operations(), support.metadataFactory(), Widget.class);
        namedQueries = new NamedQueryRegistry(
                support.operations(), support.dialect(), support.metadataFactory(), Widget.class);

        support.operations().save(new Widget("Alpha", new BigDecimal("10"))).block();
        support.operations().save(new Widget("Beta", new BigDecimal("30"))).block();
        support.operations().save(new Widget("Gamma", new BigDecimal("20"))).block();
    }

    @Test
    void entityResultMapsRowsToEntity() {
        NativeQuery query = new NativeQuery(
                "SELECT \"id\", \"name\" AS \"w_name\", \"price\" FROM \"rsm_widget\" "
                        + "WHERE \"price\" >= ? ORDER BY \"name\"",
                List.of(new BigDecimal("20")));
        StepVerifier.create(mappings.queryNative(query, "widgetEntity"))
                .assertNext(result -> {
                    Widget w = (Widget) result;
                    assertEquals("Beta", w.getName());
                    assertEquals(0, w.getPrice().compareTo(new BigDecimal("30")));
                })
                .assertNext(result -> assertEquals("Gamma", ((Widget) result).getName()))
                .verifyComplete();
    }

    @Test
    void constructorResultMapsRowsToDto() {
        String sql = "SELECT \"name\" AS \"dto_name\", \"price\" AS \"dto_price\" FROM \"rsm_widget\" "
                + "ORDER BY \"name\"";
        StepVerifier.create(mappings.queryNative(sql, "widgetView"))
                .assertNext(result -> {
                    WidgetView view = (WidgetView) result;
                    assertEquals("Alpha", view.name());
                    assertEquals(0, view.price().compareTo(new BigDecimal("10")));
                })
                .assertNext(result -> assertEquals("Beta", ((WidgetView) result).name()))
                .assertNext(result -> assertEquals("Gamma", ((WidgetView) result).name()))
                .verifyComplete();
    }

    @Test
    void columnResultMapsScalar() {
        String sql = "SELECT COUNT(*) AS \"cnt\" FROM \"rsm_widget\" WHERE \"price\" >= 20";
        StepVerifier.create(mappings.queryNative(sql, "widgetCount"))
                .assertNext(count -> assertEquals(2L, count))
                .verifyComplete();
    }

    @Test
    void namedNativeQueryWithResultSetMappingRoundTrips() {
        StepVerifier.create(
                        mappings.createNativeQuery(namedQueries, "Widget.byNameMapped")
                                .setParameter("name", "Beta")
                                .getSingleResult())
                .assertNext(result -> {
                    Widget w = (Widget) result;
                    assertEquals("Beta", w.getName());
                    assertEquals(0, w.getPrice().compareTo(new BigDecimal("30")));
                })
                .verifyComplete();
    }

    // ------------------------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------------------------

    public record WidgetView(String name, BigDecimal price) {
    }

    @Entity
    @Table(name = "rsm_widget")
    @SqlResultSetMapping(name = "widgetEntity",
            entities = @EntityResult(entityClass = Widget.class,
                    fields = @FieldResult(name = "name", column = "w_name")))
    @SqlResultSetMapping(name = "widgetView",
            classes = @ConstructorResult(targetClass = WidgetView.class,
                    columns = {@ColumnResult(name = "dto_name"),
                            @ColumnResult(name = "dto_price", type = BigDecimal.class)}))
    @SqlResultSetMapping(name = "widgetCount", columns = @ColumnResult(name = "cnt", type = Long.class))
    @NamedNativeQuery(name = "Widget.byNameMapped",
            query = "SELECT \"id\", \"name\" AS \"w_name\", \"price\" FROM \"rsm_widget\" WHERE \"name\" = :name",
            resultSetMapping = "widgetEntity")
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
