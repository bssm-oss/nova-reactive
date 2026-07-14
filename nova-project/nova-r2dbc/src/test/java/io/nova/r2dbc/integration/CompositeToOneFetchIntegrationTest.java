package io.nova.r2dbc.integration;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinColumns;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import io.nova.core.SqlExecutionListener;
import io.nova.query.QuerySpec;
import io.nova.schema.SchemaInitializer;
import io.nova.schema.SimpleSchemaInitializer;
import io.nova.sql.SqlStatement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 복합키({@code @EmbeddedId}) 엔티티를 {@code @ManyToOne}으로 참조하는 to-one이 findAll에서 <b>완전히</b>
 * hydrate되는지(id-stub이 아니라 다른 필드까지 non-null), 그리고 부모 로드가 자식 수와 무관하게 <b>한 번</b>의
 * 배치 쿼리로 끝나(N+1 없음) 각 자식이 <b>올바른</b> 부모에 매칭되는지를 실제 H2 in-memory R2DBC driver로
 * end-to-end 검증한다.
 *
 * <p>사전 동작: 이전에는 복합 to-one이 mapRow에서 복합 {@code @Id}만 채운 stub으로 노출되고 자동 fetch group에서
 * 제외돼 참조 대상의 나머지 필드가 로드되지 않았다. 이제 stub의 복합 {@code @Id}를 대상 튜플로 모아 컴포넌트별
 * 동등 조건의 OR 확장 쿼리 한 번으로 완전 엔티티를 배치 로드한다.
 */
class CompositeToOneFetchIntegrationTest {
    private H2IntegrationTestSupport support;
    private RecordingSqlExecutionListener listener;

    @BeforeEach
    void setUp() {
        listener = new RecordingSqlExecutionListener();
        support = H2IntegrationTestSupport.createWithManagedTransactions(listener);
        SchemaInitializer schema =
                new SimpleSchemaInitializer(support.operations(), support.metadataFactory(), support.dialect());
        schema.create(Region.class, Store.class, Category.class, Product.class,
                Warehouse.class, Bin.class).block();
    }

    @Test
    void compositeToOneIsFullyHydratedForEveryChildWithoutNPlusOne() {
        Region americas = region("US", 1, "americas");
        // 같은 복합 부모(US,1)를 참조하는 자식 3개 — 부모 로드가 1회로 묶여야(N+1 없음) 한다.
        StepVerifier.create(
                        support.operations().save(americas)
                                .then(support.operations().save(store("north", "US", 1)))
                                .then(support.operations().save(store("south", "US", 1)))
                                .then(support.operations().save(store("central", "US", 1))))
                .expectNextCount(1)
                .verifyComplete();

        listener.reset();
        StepVerifier.create(support.operations().findAll(Store.class, QuerySpec.empty()).collectList())
                .assertNext(stores -> {
                    assertEquals(3, stores.size());
                    for (Store store : stores) {
                        Region parent = store.getRegion();
                        assertNotNull(parent, "복합 to-one 참조가 hydrate돼야 한다");
                        assertNotNull(parent.getId(), "복합 @Id stub이 유지돼야 한다");
                        assertEquals("US", parent.getId().getCountry());
                        assertEquals(1, parent.getId().getCode());
                        // id뿐 아니라 참조 대상의 다른 필드까지 완전 로드돼야 한다(핵심 검증).
                        assertEquals("americas", parent.getName(),
                                "참조 대상이 id-stub이 아니라 완전 엔티티로 로드돼야 한다");
                    }
                })
                .verifyComplete();

        // 자식이 3개여도 부모(region) SELECT는 정확히 1회 — N+1이 없음을 증명한다.
        assertEquals(1, listener.selectCountFor("region"),
                "복합 부모는 자식 수와 무관하게 한 번의 배치 쿼리로 로드돼야 한다");
    }

    @Test
    void eachChildMatchesItsOwnCompositeParent() {
        Region americas = region("US", 1, "americas");
        Region asia = region("KR", 2, "asia");
        StepVerifier.create(
                        support.operations().save(americas)
                                .then(support.operations().save(asia))
                                .then(support.operations().save(store("north", "US", 1)))
                                .then(support.operations().save(store("seoul", "KR", 2)))
                                .then(support.operations().save(store("south", "US", 1))))
                .expectNextCount(1)
                .verifyComplete();

        listener.reset();
        StepVerifier.create(support.operations().findAll(Store.class, QuerySpec.empty()).collectList())
                .assertNext(stores -> {
                    assertEquals(3, stores.size());
                    for (Store store : stores) {
                        Region parent = store.getRegion();
                        assertNotNull(parent);
                        // 각 자식이 자기 FK 튜플에 맞는 부모로 정확히 매칭돼야 한다(wrong-object 방지).
                        if ("seoul".equals(store.getLabel())) {
                            assertEquals("KR", parent.getId().getCountry());
                            assertEquals(2, parent.getId().getCode());
                            assertEquals("asia", parent.getName());
                        } else {
                            assertEquals("US", parent.getId().getCountry());
                            assertEquals(1, parent.getId().getCode());
                            assertEquals("americas", parent.getName());
                        }
                    }
                })
                .verifyComplete();

        // 서로 다른 복합 부모 2개도 튜플 de-dup 후 한 번의 OR 확장 쿼리로 로드된다.
        assertEquals(1, listener.selectCountFor("region"));
    }

    @Test
    void nullCompositeReferenceStaysNull() {
        // 옵셔널 관계: FK 컬럼이 null인 자식은 참조가 null로 남아야 한다(빈 stub 생성 금지).
        StepVerifier.create(support.operations().save(store("orphan", null, null)))
                .expectNextCount(1)
                .verifyComplete();

        StepVerifier.create(support.operations().findAll(Store.class, QuerySpec.empty()).collectList())
                .assertNext(stores -> {
                    assertEquals(1, stores.size());
                    assertNull(stores.get(0).getRegion(), "null 복합 FK 참조는 null로 남아야 한다");
                })
                .verifyComplete();
    }

    @Test
    void danglingCompositeReferenceKeepsIdStub() {
        // 대상 region 행이 없는 FK를 가진 store(dangling FK). 참조는 null이 아니라 복합 @Id만 채운 id-stub으로
        // 보존돼야 한다 — 배치 로드가 대상을 못 찾았다고 참조를 null로 덮으면 silent 데이터 손실이 된다.
        StepVerifier.create(support.operations().save(store("lonely", "ZZ", 99)))
                .expectNextCount(1)
                .verifyComplete();

        StepVerifier.create(support.operations().findAll(Store.class, QuerySpec.empty()).collectList())
                .assertNext(stores -> {
                    assertEquals(1, stores.size());
                    Region parent = stores.get(0).getRegion();
                    assertNotNull(parent, "dangling FK 참조는 id-stub으로 보존돼야 한다(null 아님)");
                    assertNotNull(parent.getId());
                    assertEquals("ZZ", parent.getId().getCountry());
                    assertEquals(99, parent.getId().getCode());
                    assertNull(parent.getName(), "대상 행이 없으므로 복합 @Id 외 필드는 로드되지 않은 채로 남는다");
                })
                .verifyComplete();
    }

    @Test
    void idClassCompositeToOneIsFullyHydrated() {
        // @IdClass 복합 타겟도 @EmbeddedId와 동일하게 id-stub이 아니라 완전 엔티티로 배치 hydrate돼야 한다.
        Warehouse hub = warehouse("A", 1, "north-hub");
        StepVerifier.create(
                        support.operations().save(hub)
                                .then(support.operations().save(bin("b1", "A", 1)))
                                .then(support.operations().save(bin("b2", "A", 1))))
                .expectNextCount(1)
                .verifyComplete();

        listener.reset();
        StepVerifier.create(support.operations().findAll(Bin.class, QuerySpec.empty()).collectList())
                .assertNext(bins -> {
                    assertEquals(2, bins.size());
                    for (Bin bin : bins) {
                        Warehouse wh = bin.getWarehouse();
                        assertNotNull(wh, "@IdClass 복합 to-one 참조가 hydrate돼야 한다");
                        assertEquals("A", wh.getZone());
                        assertEquals(1, wh.getSlot());
                        assertEquals("north-hub", wh.getTitle(),
                                "@IdClass 복합 타겟이 id-stub이 아니라 완전 엔티티로 로드돼야 한다");
                    }
                })
                .verifyComplete();

        assertEquals(1, listener.selectCountFor("warehouse"),
                "@IdClass 복합 부모도 자식 수와 무관하게 한 번의 배치 쿼리로 로드돼야 한다");
    }

    @Test
    void singleKeyToOneHasNoRegression() {
        // 단일키 to-one auto-hydration 회귀 0 — 복합 경로 도입이 기존 단일 FK IN 경로를 바꾸지 않는다.
        Category tools = new Category();
        tools.setName("tools");
        StepVerifier.create(
                        support.operations().save(tools)
                                .flatMap(saved -> support.operations().save(product("hammer", saved))
                                        .then(support.operations().save(product("wrench", saved)))))
                .expectNextCount(1)
                .verifyComplete();

        listener.reset();
        StepVerifier.create(support.operations().findAll(Product.class, QuerySpec.empty()).collectList())
                .assertNext(products -> {
                    assertEquals(2, products.size());
                    for (Product product : products) {
                        assertNotNull(product.getCategory());
                        assertEquals("tools", product.getCategory().getName(),
                                "단일키 참조 대상도 완전 로드돼야 한다");
                    }
                })
                .verifyComplete();

        assertEquals(1, listener.selectCountFor("category"),
                "단일키 부모도 자식 수와 무관하게 한 번의 IN 쿼리로 로드돼야 한다");
    }

    private static Region region(String country, Integer code, String name) {
        Region region = new Region();
        region.setId(new RegionKey(country, code));
        region.setName(name);
        return region;
    }

    private static Store store(String label, String country, Integer code) {
        Store store = new Store();
        store.setLabel(label);
        if (country != null || code != null) {
            Region ref = new Region();
            ref.setId(new RegionKey(country, code));
            store.setRegion(ref);
        }
        return store;
    }

    private static Product product(String label, Category category) {
        Product product = new Product();
        product.setLabel(label);
        product.setCategory(category);
        return product;
    }

    private static Warehouse warehouse(String zone, Integer slot, String title) {
        Warehouse warehouse = new Warehouse();
        warehouse.setZone(zone);
        warehouse.setSlot(slot);
        warehouse.setTitle(title);
        return warehouse;
    }

    private static Bin bin(String label, String zone, Integer slot) {
        Bin bin = new Bin();
        bin.setLabel(label);
        Warehouse ref = new Warehouse();
        ref.setZone(zone);
        ref.setSlot(slot);
        bin.setWarehouse(ref);
        return bin;
    }

    /** 실행된 SQL 문장을 모아 특정 테이블에 대한 SELECT 발행 횟수를 세는 listener. */
    private static final class RecordingSqlExecutionListener implements SqlExecutionListener {
        private final List<String> statements = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void onBeforeExecution(SqlStatement statement) {
            statements.add(statement.sql());
        }

        void reset() {
            statements.clear();
        }

        long selectCountFor(String table) {
            String needle = "from \"" + table + "\"";
            synchronized (statements) {
                return statements.stream()
                        .map(sql -> sql.toLowerCase())
                        .filter(sql -> sql.startsWith("select"))
                        .filter(sql -> sql.contains(needle))
                        .count();
            }
        }
    }

    // --- composite-key parent + child --------------------------------------

    @Embeddable
    public static class RegionKey {
        @Column(name = "country")
        private String country;
        @Column(name = "code")
        private Integer code;

        public RegionKey() {
        }

        public RegionKey(String country, Integer code) {
            this.country = country;
            this.code = code;
        }

        public String getCountry() {
            return country;
        }

        public Integer getCode() {
            return code;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof RegionKey that
                    && Objects.equals(country, that.country) && Objects.equals(code, that.code);
        }

        @Override
        public int hashCode() {
            return Objects.hash(country, code);
        }
    }

    @Entity
    @Table(name = "region")
    public static class Region {
        @EmbeddedId
        private RegionKey id;

        @Column(name = "name")
        private String name;

        public Region() {
        }

        public RegionKey getId() {
            return id;
        }

        public void setId(RegionKey id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }

    @Entity
    @Table(name = "store")
    public static class Store {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        @Column(name = "id")
        private Long id;

        @Column(name = "label")
        private String label;

        @ManyToOne(targetEntity = Region.class)
        @JoinColumns({
                @JoinColumn(name = "region_country", referencedColumnName = "country"),
                @JoinColumn(name = "region_code", referencedColumnName = "code")
        })
        private Region region;

        public Store() {
        }

        public Long getId() {
            return id;
        }

        public String getLabel() {
            return label;
        }

        public void setLabel(String label) {
            this.label = label;
        }

        public Region getRegion() {
            return region;
        }

        public void setRegion(Region region) {
            this.region = region;
        }
    }

    // --- single-key parent + child (regression baseline) -------------------

    @Entity
    @Table(name = "category")
    public static class Category {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        @Column(name = "id")
        private Long id;

        @Column(name = "name")
        private String name;

        public Category() {
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
    @Table(name = "product")
    public static class Product {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        @Column(name = "id")
        private Long id;

        @Column(name = "label")
        private String label;

        @ManyToOne(targetEntity = Category.class)
        @JoinColumn(name = "category_id")
        private Category category;

        public Product() {
        }

        public Long getId() {
            return id;
        }

        public String getLabel() {
            return label;
        }

        public void setLabel(String label) {
            this.label = label;
        }

        public Category getCategory() {
            return category;
        }

        public void setCategory(Category category) {
            this.category = category;
        }
    }

    // --- @IdClass composite parent + child ---------------------------------

    public static class WarehouseKey {
        private String zone;
        private Integer slot;

        public WarehouseKey() {
        }

        public WarehouseKey(String zone, Integer slot) {
            this.zone = zone;
            this.slot = slot;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof WarehouseKey that
                    && Objects.equals(zone, that.zone) && Objects.equals(slot, that.slot);
        }

        @Override
        public int hashCode() {
            return Objects.hash(zone, slot);
        }
    }

    @Entity
    @Table(name = "warehouse")
    @IdClass(WarehouseKey.class)
    public static class Warehouse {
        @Id
        @Column(name = "zone")
        private String zone;
        @Id
        @Column(name = "slot")
        private Integer slot;
        @Column(name = "title")
        private String title;

        public Warehouse() {
        }

        public String getZone() {
            return zone;
        }

        public void setZone(String zone) {
            this.zone = zone;
        }

        public Integer getSlot() {
            return slot;
        }

        public void setSlot(Integer slot) {
            this.slot = slot;
        }

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }
    }

    @Entity
    @Table(name = "bin")
    public static class Bin {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        @Column(name = "id")
        private Long id;

        @Column(name = "label")
        private String label;

        @ManyToOne(targetEntity = Warehouse.class)
        @JoinColumns({
                @JoinColumn(name = "wh_zone", referencedColumnName = "zone"),
                @JoinColumn(name = "wh_slot", referencedColumnName = "slot")
        })
        private Warehouse warehouse;

        public Bin() {
        }

        public Long getId() {
            return id;
        }

        public String getLabel() {
            return label;
        }

        public void setLabel(String label) {
            this.label = label;
        }

        public Warehouse getWarehouse() {
            return warehouse;
        }

        public void setWarehouse(Warehouse warehouse) {
            this.warehouse = warehouse;
        }
    }
}
