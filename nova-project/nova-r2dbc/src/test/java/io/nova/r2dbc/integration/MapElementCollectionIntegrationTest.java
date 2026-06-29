package io.nova.r2dbc.integration;

import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MapKeyColumn;
import jakarta.persistence.MapKeyEnumerated;
import jakarta.persistence.Table;
import io.nova.schema.SchemaInitializer;
import io.nova.schema.SimpleSchemaInitializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code @ElementCollection Map<K,V>}가 H2 in-memory R2DBC driver와 end-to-end로 동작하는지 검증한다 —
 * (owner FK, key, value) collection table DDL, save 시 full-replace 동기화, findById 시 Map 복원. 기본 타입 key와
 * enum key(STRING/ORDINAL)를 모두 다룬다.
 */
class MapElementCollectionIntegrationTest {
    private H2IntegrationTestSupport support;

    @BeforeEach
    void setUp() {
        support = H2IntegrationTestSupport.create();
        SchemaInitializer schema =
                new SimpleSchemaInitializer(support.operations(), support.metadataFactory(), support.dialect());
        schema.create(Catalog.class).block();
    }

    @Test
    void restoresBasicKeyMap() {
        Catalog catalog = new Catalog();
        catalog.getScores().put("alpha", 1);
        catalog.getScores().put("beta", 2);
        Long id = support.operations().save(catalog).map(Catalog::getId).block();

        StepVerifier.create(support.operations().findById(Catalog.class, id))
                .assertNext(loaded -> {
                    assertEquals(2, loaded.getScores().size());
                    assertEquals(1, loaded.getScores().get("alpha"));
                    assertEquals(2, loaded.getScores().get("beta"));
                })
                .verifyComplete();
    }

    @Test
    void restoresStringEnumKeyMap() {
        Catalog catalog = new Catalog();
        catalog.getNotes().put(Weekday.MON, "gym");
        catalog.getNotes().put(Weekday.WED, "swim");
        Long id = support.operations().save(catalog).map(Catalog::getId).block();

        StepVerifier.create(support.operations().findById(Catalog.class, id))
                .assertNext(loaded -> {
                    assertEquals(2, loaded.getNotes().size());
                    assertEquals("gym", loaded.getNotes().get(Weekday.MON));
                    assertEquals("swim", loaded.getNotes().get(Weekday.WED));
                })
                .verifyComplete();
    }

    @Test
    void restoresOrdinalEnumKeyMap() {
        Catalog catalog = new Catalog();
        catalog.getRanks().put(Weekday.TUE, 10);
        catalog.getRanks().put(Weekday.WED, 20);
        Long id = support.operations().save(catalog).map(Catalog::getId).block();

        StepVerifier.create(support.operations().findById(Catalog.class, id))
                .assertNext(loaded -> {
                    assertEquals(2, loaded.getRanks().size());
                    assertEquals(10, loaded.getRanks().get(Weekday.TUE));
                    assertEquals(20, loaded.getRanks().get(Weekday.WED));
                })
                .verifyComplete();
    }

    @Test
    void reSaveFullReplacesEntries() {
        Catalog catalog = new Catalog();
        catalog.getScores().put("alpha", 1);
        catalog.getScores().put("beta", 2);
        Long id = support.operations().save(catalog).map(Catalog::getId).block();

        Catalog loaded = support.operations().findById(Catalog.class, id).block();
        loaded.getScores().clear();
        loaded.getScores().put("gamma", 3);
        support.operations().save(loaded).block();

        StepVerifier.create(support.operations().findById(Catalog.class, id))
                .assertNext(reloaded -> {
                    assertEquals(1, reloaded.getScores().size());
                    assertEquals(3, reloaded.getScores().get("gamma"));
                })
                .verifyComplete();
    }

    enum Weekday { MON, TUE, WED }

    @Entity
    @Table(name = "catalog")
    public static class Catalog {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;

        // 컬렉션만 있는 엔티티는 re-save UPDATE의 SET 절이 비어 부적합하므로 스칼라 컬럼을 하나 둔다.
        private String name = "catalog";

        @ElementCollection
        private Map<String, Integer> scores = new HashMap<>();

        @ElementCollection
        @MapKeyColumn(name = "day")
        @MapKeyEnumerated(EnumType.STRING)
        private Map<Weekday, String> notes = new HashMap<>();

        @ElementCollection
        @MapKeyColumn(name = "day")
        private Map<Weekday, Integer> ranks = new HashMap<>();

        public Catalog() {
        }

        public Long getId() {
            return id;
        }

        public Map<String, Integer> getScores() {
            return scores;
        }

        public Map<Weekday, String> getNotes() {
            return notes;
        }

        public Map<Weekday, Integer> getRanks() {
            return ranks;
        }
    }
}
