package io.nova.r2dbc.integration;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import io.nova.schema.SchemaInitializer;
import io.nova.schema.SimpleSchemaInitializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code @ElementCollection}의 {@code @Embeddable} 원소 타입이 H2 in-memory R2DBC driver와 end-to-end로
 * 동작하는지 검증한다 — 다중 컬럼 collection table DDL 생성, save 시 full-replace 동기화, 1-hop hydration.
 */
class EmbeddableElementCollectionIntegrationTest {
    private H2IntegrationTestSupport support;

    @BeforeEach
    void setUp() {
        support = H2IntegrationTestSupport.create();
        SchemaInitializer schema =
                new SimpleSchemaInitializer(support.operations(), support.metadataFactory(), support.dialect());
        // route 테이블과 @Embeddable @ElementCollection collection table(route_legs)을 생성한다.
        schema.create(Route.class).block();
    }

    @Test
    void savesEmbeddableElementsAndHydrates() {
        Route route = new Route("east");
        route.getLegs().add(new Leg("seoul", "busan", 325));
        route.getLegs().add(new Leg("busan", "ulsan", 60));
        Long id = support.operations().save(route).map(Route::getId).block();

        StepVerifier.create(support.operations().findById(Route.class, id))
                .assertNext(loaded -> {
                    assertEquals(2, loaded.getLegs().size());
                    assertTrue(loaded.getLegs().contains(new Leg("seoul", "busan", 325)));
                    assertTrue(loaded.getLegs().contains(new Leg("busan", "ulsan", 60)));
                })
                .verifyComplete();
    }

    @Test
    void reSaveFullReplacesEmbeddableElements() {
        Route route = new Route("east");
        route.getLegs().add(new Leg("seoul", "busan", 325));
        Long id = support.operations().save(route).map(Route::getId).block();

        Route loaded = support.operations().findById(Route.class, id).block();
        loaded.getLegs().clear();
        loaded.getLegs().add(new Leg("seoul", "daegu", 240));
        support.operations().save(loaded).block();

        StepVerifier.create(support.operations().findById(Route.class, id))
                .assertNext(reloaded -> assertEquals(
                        List.of(new Leg("seoul", "daegu", 240)), reloaded.getLegs()))
                .verifyComplete();
    }

    @Test
    void emptyCollectionClearsEmbeddableElements() {
        Route route = new Route("east");
        route.getLegs().add(new Leg("seoul", "busan", 325));
        Long id = support.operations().save(route).map(Route::getId).block();

        Route loaded = support.operations().findById(Route.class, id).block();
        loaded.getLegs().clear();
        support.operations().save(loaded).block();

        StepVerifier.create(support.operations().findById(Route.class, id))
                .assertNext(reloaded -> assertEquals(0, reloaded.getLegs().size()))
                .verifyComplete();
    }

    @Embeddable
    public static class Leg {
        @Column(name = "origin")
        private String from;
        private String to;
        private int distance;

        public Leg() {
        }

        public Leg(String from, String to, int distance) {
            this.from = from;
            this.to = to;
            this.distance = distance;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Leg leg)) {
                return false;
            }
            return distance == leg.distance
                    && Objects.equals(from, leg.from)
                    && Objects.equals(to, leg.to);
        }

        @Override
        public int hashCode() {
            return Objects.hash(from, to, distance);
        }
    }

    @Entity
    @Table(name = "route")
    public static class Route {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;
        private String name;

        @ElementCollection
        @AttributeOverride(name = "to", column = @Column(name = "destination"))
        private List<Leg> legs = new ArrayList<>();

        public Route() {
        }

        public Route(String name) {
            this.name = name;
        }

        public Long getId() {
            return id;
        }

        public String getName() {
            return name;
        }

        public List<Leg> getLegs() {
            return legs;
        }
    }
}
