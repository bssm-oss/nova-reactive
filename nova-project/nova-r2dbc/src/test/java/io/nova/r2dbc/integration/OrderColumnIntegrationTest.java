package io.nova.r2dbc.integration;

import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import io.nova.schema.SchemaInitializer;
import io.nova.schema.SimpleSchemaInitializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code @OrderColumn}으로 정렬된 {@code @ElementCollection} List가 H2 in-memory R2DBC driver와 end-to-end로
 * 동작하는지 검증한다 — order 컬럼 DDL, save 시 0..n-1 인덱스 기록, findById 시 order 컬럼 ORDER BY로 순서 복원.
 */
class OrderColumnIntegrationTest {
    private H2IntegrationTestSupport support;

    @BeforeEach
    void setUp() {
        support = H2IntegrationTestSupport.create();
        SchemaInitializer schema =
                new SimpleSchemaInitializer(support.operations(), support.metadataFactory(), support.dialect());
        schema.create(Playlist.class).block();
        schema.create(Route.class).block();
    }

    @Test
    void restoresListOrderFromOrderColumn() {
        // 알파벳 순서가 아닌 의도적으로 섞은 순서로 저장한다 — IN-query 기본 순서가 아니라 order 컬럼이 복원하는지 확인.
        Playlist playlist = new Playlist();
        playlist.getTracks().add("gamma");
        playlist.getTracks().add("alpha");
        playlist.getTracks().add("beta");
        Long id = support.operations().save(playlist).map(Playlist::getId).block();

        StepVerifier.create(support.operations().findById(Playlist.class, id))
                .assertNext(loaded -> assertEquals(List.of("gamma", "alpha", "beta"), loaded.getTracks()))
                .verifyComplete();
    }

    @Test
    void recomputesIndicesAfterReorderAndAddRemove() {
        Playlist playlist = new Playlist();
        playlist.getTracks().add("a");
        playlist.getTracks().add("b");
        playlist.getTracks().add("c");
        Long id = support.operations().save(playlist).map(Playlist::getId).block();

        Playlist loaded = support.operations().findById(Playlist.class, id).block();
        // 재정렬 + 원소 삭제(c 제거) + 추가(d) → 인덱스가 0..n-1로 재계산되어야 한다.
        loaded.getTracks().clear();
        loaded.getTracks().add("b");
        loaded.getTracks().add("d");
        loaded.getTracks().add("a");
        support.operations().save(loaded).block();

        StepVerifier.create(support.operations().findById(Playlist.class, id))
                .assertNext(reloaded -> assertEquals(List.of("b", "d", "a"), reloaded.getTracks()))
                .verifyComplete();
    }

    @Test
    void preservesOrderAcrossMultipleOwners() {
        Playlist first = new Playlist();
        first.getTracks().add("x1");
        first.getTracks().add("x2");
        Long firstId = support.operations().save(first).map(Playlist::getId).block();

        Playlist second = new Playlist();
        second.getTracks().add("y1");
        second.getTracks().add("y2");
        second.getTracks().add("y3");
        Long secondId = support.operations().save(second).map(Playlist::getId).block();

        StepVerifier.create(support.operations().findById(Playlist.class, firstId))
                .assertNext(loaded -> assertEquals(List.of("x1", "x2"), loaded.getTracks()))
                .verifyComplete();
        StepVerifier.create(support.operations().findById(Playlist.class, secondId))
                .assertNext(loaded -> assertEquals(List.of("y1", "y2", "y3"), loaded.getTracks()))
                .verifyComplete();
    }

    @Test
    void restoresOrderForEmbeddableElements() {
        Route route = new Route();
        route.getLegs().add(new Leg("home", "office"));
        route.getLegs().add(new Leg("office", "gym"));
        route.getLegs().add(new Leg("gym", "home"));
        Long id = support.operations().save(route).map(Route::getId).block();

        StepVerifier.create(support.operations().findById(Route.class, id))
                .assertNext(loaded -> {
                    List<Leg> legs = loaded.getLegs();
                    assertEquals(3, legs.size());
                    assertEquals(List.of("home", "office", "gym"),
                            legs.stream().map(Leg::getOrigin).toList());
                    assertEquals(List.of("office", "gym", "home"),
                            legs.stream().map(Leg::getDest).toList());
                })
                .verifyComplete();
    }

    @Entity
    @Table(name = "playlist")
    public static class Playlist {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;

        // 컬렉션만 있는 엔티티는 re-save UPDATE의 SET 절이 비어 부적합하므로 스칼라 컬럼을 하나 둔다.
        private String name = "list";

        @ElementCollection
        @OrderColumn
        private List<String> tracks = new ArrayList<>();

        public Playlist() {
        }

        public Long getId() {
            return id;
        }

        public List<String> getTracks() {
            return tracks;
        }
    }

    @Embeddable
    public static class Leg {
        private String origin;
        private String dest;

        public Leg() {
        }

        public Leg(String origin, String dest) {
            this.origin = origin;
            this.dest = dest;
        }

        public String getOrigin() {
            return origin;
        }

        public String getDest() {
            return dest;
        }
    }

    @Entity
    @Table(name = "route")
    public static class Route {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;

        private String name = "route";

        @ElementCollection
        @OrderColumn
        private List<Leg> legs = new ArrayList<>();

        public Route() {
        }

        public Long getId() {
            return id;
        }

        public List<Leg> getLegs() {
            return legs;
        }
    }
}
