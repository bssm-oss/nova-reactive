package io.nova.r2dbc.integration;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MapKeyClass;
import jakarta.persistence.Table;
import io.nova.schema.SchemaInitializer;
import io.nova.schema.SimpleSchemaInitializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code @MapKeyClass}가 {@code @Embeddable} key 클래스를 가리키는 {@code @ElementCollection Map<K,V>}가 H2
 * in-memory R2DBC driver와 end-to-end로 동작하는지 검증한다 — key를 다중 컬럼으로 펼친 collection table DDL,
 * save 시 full-replace 동기화, findById 시 key 인스턴스 재구성. 기본 타입 value와 {@code @Embeddable} value를
 * 모두 다룬다.
 */
class EmbeddableMapKeyElementCollectionIntegrationTest {
    private H2IntegrationTestSupport support;

    @BeforeEach
    void setUp() {
        support = H2IntegrationTestSupport.create();
        SchemaInitializer schema =
                new SimpleSchemaInitializer(support.operations(), support.metadataFactory(), support.dialect());
        schema.create(Store.class).block();
    }

    @Test
    void restoresEmbeddableKeyWithBasicValue() {
        Store store = new Store();
        store.getTags().put(new GeoKey("EU", 1), "alpha");
        store.getTags().put(new GeoKey("US", 2), "beta");
        Long id = support.operations().save(store).map(Store::getId).block();

        StepVerifier.create(support.operations().findById(Store.class, id))
                .assertNext(loaded -> {
                    assertEquals(2, loaded.getTags().size());
                    assertEquals("alpha", loaded.getTags().get(new GeoKey("EU", 1)));
                    assertEquals("beta", loaded.getTags().get(new GeoKey("US", 2)));
                })
                .verifyComplete();
    }

    @Test
    void restoresEmbeddableKeyWithEmbeddableValue() {
        Store store = new Store();
        store.getPrices().put(new GeoKey("EU", 1), new Amount("EUR", 1000));
        store.getPrices().put(new GeoKey("US", 2), new Amount("USD", 2500));
        Long id = support.operations().save(store).map(Store::getId).block();

        StepVerifier.create(support.operations().findById(Store.class, id))
                .assertNext(loaded -> {
                    assertEquals(2, loaded.getPrices().size());
                    assertEquals(new Amount("EUR", 1000), loaded.getPrices().get(new GeoKey("EU", 1)));
                    assertEquals(new Amount("USD", 2500), loaded.getPrices().get(new GeoKey("US", 2)));
                })
                .verifyComplete();
    }

    @Test
    void restoresKeyOverriddenEmbeddableKeyWithEmbeddableValue() {
        Store store = new Store();
        store.getRoutes().put(new GeoKey("EU", 1), new Amount("EUR", 1000));
        store.getRoutes().put(new GeoKey("US", 2), new Amount("USD", 2500));
        Long id = support.operations().save(store).map(Store::getId).block();

        StepVerifier.create(support.operations().findById(Store.class, id))
                .assertNext(loaded -> {
                    assertEquals(2, loaded.getRoutes().size());
                    assertEquals(new Amount("EUR", 1000), loaded.getRoutes().get(new GeoKey("EU", 1)));
                    assertEquals(new Amount("USD", 2500), loaded.getRoutes().get(new GeoKey("US", 2)));
                })
                .verifyComplete();
    }

    @Test
    void reSaveFullReplacesEmbeddableKeyedEntries() {
        Store store = new Store();
        store.getTags().put(new GeoKey("EU", 1), "alpha");
        store.getTags().put(new GeoKey("US", 2), "beta");
        Long id = support.operations().save(store).map(Store::getId).block();

        Store loaded = support.operations().findById(Store.class, id).block();
        loaded.getTags().clear();
        loaded.getTags().put(new GeoKey("APAC", 3), "gamma");
        support.operations().save(loaded).block();

        StepVerifier.create(support.operations().findById(Store.class, id))
                .assertNext(reloaded -> {
                    assertEquals(1, reloaded.getTags().size());
                    assertEquals("gamma", reloaded.getTags().get(new GeoKey("APAC", 3)));
                })
                .verifyComplete();
    }

    @Embeddable
    public static class GeoKey {
        private String region;
        private int zone;

        public GeoKey() {
        }

        public GeoKey(String region, int zone) {
            this.region = region;
            this.zone = zone;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof GeoKey that)) {
                return false;
            }
            return zone == that.zone && Objects.equals(region, that.region);
        }

        @Override
        public int hashCode() {
            return Objects.hash(region, zone);
        }
    }

    @Embeddable
    public static class Amount {
        private String currency;
        private int cents;

        public Amount() {
        }

        public Amount(String currency, int cents) {
            this.currency = currency;
            this.cents = cents;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Amount that)) {
                return false;
            }
            return cents == that.cents && Objects.equals(currency, that.currency);
        }

        @Override
        public int hashCode() {
            return Objects.hash(currency, cents);
        }
    }

    @Entity
    @Table(name = "store")
    public static class Store {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;

        // 컬렉션만 있는 엔티티는 re-save UPDATE의 SET 절이 비어 부적합하므로 스칼라 컬럼을 하나 둔다.
        private String name = "store";

        @ElementCollection
        @MapKeyClass(GeoKey.class)
        private Map<GeoKey, String> tags = new HashMap<>();

        @ElementCollection
        @MapKeyClass(GeoKey.class)
        private Map<GeoKey, Amount> prices = new HashMap<>();

        // @Embeddable key 컬럼을 key.* override로 이름 변경 + @Embeddable value 조합.
        @ElementCollection
        @MapKeyClass(GeoKey.class)
        @AttributeOverride(name = "key.region", column = @Column(name = "from_region"))
        @AttributeOverride(name = "key.zone", column = @Column(name = "from_zone"))
        private Map<GeoKey, Amount> routes = new HashMap<>();

        public Store() {
        }

        public Long getId() {
            return id;
        }

        public Map<GeoKey, String> getTags() {
            return tags;
        }

        public Map<GeoKey, Amount> getPrices() {
            return prices;
        }

        public Map<GeoKey, Amount> getRoutes() {
            return routes;
        }
    }
}
