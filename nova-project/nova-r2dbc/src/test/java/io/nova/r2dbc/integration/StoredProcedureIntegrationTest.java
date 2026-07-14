package io.nova.r2dbc.integration;

import io.nova.core.ReactiveEntityManager;
import io.nova.core.SimpleReactiveEntityManager;
import io.nova.query.storedprocedure.NamedStoredProcedureRegistry;
import io.nova.query.storedprocedure.ReactiveStoredProcedureQuery;
import io.nova.query.storedprocedure.StoredProcedureException;
import io.nova.query.storedprocedure.StoredProcedureParameterDefinition;
import io.nova.schema.SchemaInitializer;
import io.nova.schema.SimpleSchemaInitializer;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.NamedStoredProcedureQuery;
import jakarta.persistence.ParameterMode;
import jakarta.persistence.StoredProcedureParameter;
import jakarta.persistence.Table;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code @NamedStoredProcedureQuery}/{@code createStoredProcedureQuery}(리액티브 저장 프로시저, W7)가 H2
 * in-memory R2DBC driver와 end-to-end로 동작하는지 검증한다.
 *
 * <p>H2 {@code CREATE ALIAS}로 Java 메서드를 result-set 프로시저로 등록한 뒤, IN 파라미터를 바인딩해 CALL 하고
 * 결과 행을 엔티티/커스텀 매퍼로 매핑한다. r2dbc-h2 driver는 출력 파라미터를 지원하지 않으므로
 * OUT/INOUT/REF_CURSOR는 실행 전에 {@link StoredProcedureException}으로 fail-fast 한다.
 */
class StoredProcedureIntegrationTest {

    private H2IntegrationTestSupport support;
    private NamedStoredProcedureRegistry registry;

    @BeforeEach
    void setUp() {
        support = H2IntegrationTestSupport.create();
        SchemaInitializer schema =
                new SimpleSchemaInitializer(support.operations(), support.metadataFactory(), support.dialect());
        schema.create(Widget.class).block();
        // H2 result-set 프로시저를 Java 메서드로 등록한다(CREATE ALIAS). alias 이름은 대문자로 정규화된다.
        support.execute("CREATE ALIAS FIND_EXPENSIVE FOR "
                + "\"io.nova.r2dbc.integration.StoredProcedureIntegrationTest$Procedures.findExpensive\"");
        registry = new NamedStoredProcedureRegistry(
                support.operations(), support.dialect(), support.metadataFactory(), Widget.class);

        support.operations().save(new Widget("Alpha", new BigDecimal("10"))).block();
        support.operations().save(new Widget("Beta", new BigDecimal("30"))).block();
        support.operations().save(new Widget("Gamma", new BigDecimal("20"))).block();
    }

    @Test
    void namedStoredProcedureReturnsMappedEntities() {
        StepVerifier.create(
                        registry.createNamedStoredProcedureQuery("Widget.findExpensive")
                                .setParameter("minPrice", new BigDecimal("20"))
                                .getResultList())
                .assertNext(result -> {
                    Widget w = (Widget) result;
                    assertEquals("Beta", w.getName());
                    assertEquals(0, w.getPrice().compareTo(new BigDecimal("30")));
                })
                .assertNext(result -> assertEquals("Gamma", ((Widget) result).getName()))
                .verifyComplete();
    }

    @Test
    void adhocStoredProcedureWithCustomMapperViaEntityManager() {
        ReactiveEntityManager em = new SimpleReactiveEntityManager(
                support.operations(), support.metadataFactory(), support.dialect());
        List<StoredProcedureParameterDefinition> params = List.of(
                new StoredProcedureParameterDefinition("minPrice", ParameterMode.IN, BigDecimal.class));

        ReactiveStoredProcedureQuery<String> query = em.createStoredProcedureQuery(
                "FIND_EXPENSIVE", params, row -> row.get("name", String.class));

        StepVerifier.create(query.setParameter("minPrice", new BigDecimal("15")).getResultList())
                .expectNext("Beta", "Gamma")
                .verifyComplete();
    }

    @Test
    void getSingleResultReturnsOneRow() {
        StepVerifier.create(
                        registry.createNamedStoredProcedureQuery("Widget.findExpensive")
                                .setParameter("minPrice", new BigDecimal("25"))
                                .getSingleResult())
                .assertNext(result -> assertEquals("Beta", ((Widget) result).getName()))
                .verifyComplete();
    }

    @Test
    void outParameterFailsFastAtExecution() {
        // r2dbc-h2 는 출력 파라미터를 지원하지 않는다 — OUT 파라미터 선언은 CALL 발행 전에 fail-fast 한다.
        StepVerifier.create(
                        registry.createNamedStoredProcedureQuery("Widget.withOut", row -> row.get("name", String.class))
                                .setParameter("minPrice", new BigDecimal("1"))
                                .getResultList())
                .verifyErrorSatisfies(error -> {
                    assertTrue(error instanceof StoredProcedureException);
                    assertTrue(error.getMessage().contains("does not support output parameters"),
                            "message was: " + error.getMessage());
                });
    }

    // ------------------------------------------------------------------------------------
    // H2 CREATE ALIAS target — must be a public class with public static methods.
    // ------------------------------------------------------------------------------------

    public static final class Procedures {
        private Procedures() {
        }

        /**
         * IN 파라미터 하나를 받아 조건에 맞는 widget 행들을 result-set 으로 반환한다. H2는 첫 인자가
         * {@link Connection}이면 현재 커넥션을 자동 주입하므로 CALL 인자는 {@code minPrice} 하나다.
         */
        public static ResultSet findExpensive(Connection conn, BigDecimal minPrice) throws SQLException {
            PreparedStatement statement = conn.prepareStatement(
                    "SELECT \"id\", \"name\", \"price\" FROM \"sp_widget\" WHERE \"price\" >= ? ORDER BY \"name\"");
            statement.setBigDecimal(1, minPrice);
            return statement.executeQuery();
        }
    }

    // ------------------------------------------------------------------------------------
    // Entity fixture
    // ------------------------------------------------------------------------------------

    @Entity
    @Table(name = "sp_widget")
    @NamedStoredProcedureQuery(name = "Widget.findExpensive", procedureName = "FIND_EXPENSIVE",
            resultClasses = Widget.class,
            parameters = @StoredProcedureParameter(name = "minPrice", mode = ParameterMode.IN, type = BigDecimal.class))
    @NamedStoredProcedureQuery(name = "Widget.withOut", procedureName = "FIND_EXPENSIVE",
            parameters = {
                    @StoredProcedureParameter(name = "minPrice", mode = ParameterMode.IN, type = BigDecimal.class),
                    @StoredProcedureParameter(name = "total", mode = ParameterMode.OUT, type = Integer.class)})
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
