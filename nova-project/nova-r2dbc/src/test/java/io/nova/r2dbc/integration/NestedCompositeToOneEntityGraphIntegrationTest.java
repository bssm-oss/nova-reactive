package io.nova.r2dbc.integration;

import io.nova.core.SqlExecutionListener;
import io.nova.graph.EntityGraph;
import io.nova.graph.EntityGraphs;
import io.nova.schema.SchemaInitializer;
import io.nova.schema.SimpleSchemaInitializer;
import io.nova.sql.SqlStatement;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinColumns;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.NamedAttributeNode;
import jakarta.persistence.NamedEntityGraph;
import jakarta.persistence.NamedSubgraph;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
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
 * 중첩 서브그래프(depth&gt;1) EntityGraph 의 <b>복합키(@EmbeddedId) to-one leaf</b> hydration 이 H2 in-memory
 * R2DBC driver 와 end-to-end 로 동작하는지 검증한다.
 *
 * <p>루트 {@code Depot} → {@code shipments}(@OneToMany) → 각 shipment 의 {@code warehouse}(복합키 타겟
 * {@code @ManyToOne}, 다중컬럼 FK)라는 2단계 그래프를 {@code @NamedEntityGraph}(@NamedSubgraph) 형태와
 * 프로그램적 {@code addSubgraph} 형태 둘 다로 조회한다. depth&gt;1 복합키 leaf 지원 전에는 build-time fail-fast
 * 됐고, flat 조회 시 warehouse 는 복합 {@code @Id} 만 채운 stub(title=NULL)이었다 — 그 대조군을 고정해 false-green
 * 을 방지하고, subgraph 를 선언하면 다중컬럼 FK(OR-of-ANDs) 배치 SELECT 한 번으로 warehouse 가 fully-load 됨을
 * 확인한다. shipment 수와 무관하게 warehouse SELECT 가 정확히 1회(N+1 없음)임도 {@code SqlExecutionListener}로
 * 고정한다.
 */
class NestedCompositeToOneEntityGraphIntegrationTest {

    private H2IntegrationTestSupport support;
    private EntityGraphs graphs;
    private final RecordingListener recorder = new RecordingListener();

    @BeforeEach
    void setUp() {
        support = H2IntegrationTestSupport.createWithManagedTransactions(recorder);
        SchemaInitializer schema =
                new SimpleSchemaInitializer(support.operations(), support.metadataFactory(), support.dialect());
        schema.create(Warehouse.class, Depot.class, Shipment.class).block();
        graphs = new EntityGraphs(support.metadataFactory());

        // 복합키 warehouse 두 개(서로 다른 (region,code) 튜플).
        support.operations().save(warehouse("US", 1, "north-hub")).block();
        support.operations().save(warehouse("KR", 2, "seoul-hub")).block();

        Depot east = support.operations().save(depot("east")).block();
        Depot west = support.operations().save(depot("west")).block();

        // east: 같은 warehouse(US,1)를 참조하는 shipment 2개(부모 로드 1회로 묶여야 N+1 없음).
        support.operations().save(shipment("s1", east, "US", 1)).block();
        support.operations().save(shipment("s2", east, "US", 1)).block();
        // west: 다른 warehouse(KR,2)를 참조하는 shipment 1개(각 shipment 가 자기 튜플의 warehouse 로 매칭).
        support.operations().save(shipment("s3", west, "KR", 2)).block();
    }

    @Test
    void flatGraphLeavesCompositeLeafAsIdStub() {
        // 대조군(false-green 방지): subgraph 없이 flat 조회하면 shipment.warehouse 는 복합 @Id 만 채운 stub 이라
        // title 이 NULL 이다(depth>1 을 로드하지 않음).
        EntityGraph<Depot> flat = graphs.building(Depot.class).addAttributeNodes("shipments").build();
        Long eastId = depotId("east");

        StepVerifier.create(support.operations().findById(Depot.class, eastId, flat))
                .assertNext(depot -> {
                    assertEquals(2, depot.getShipments().size());
                    Shipment shipment = depot.getShipments().get(0);
                    assertNotNull(shipment.getWarehouse(), "복합 to-one 은 mapRow 가 복합 @Id stub 으로 채운다");
                    assertNotNull(shipment.getWarehouse().getId(), "복합 @Id stub 은 유지된다");
                    assertNull(shipment.getWarehouse().getTitle(),
                            "flat 그래프는 depth>1 을 로드하지 않으므로 warehouse 는 title 이 비어 있는 stub 이다");
                })
                .verifyComplete();
    }

    @Test
    void namedSubgraphFindByIdLoadsCompositeWarehouse() {
        Long eastId = depotId("east");
        EntityGraph<Depot> graph = graphs.named(Depot.class, "Depot.shipmentsWarehouse");

        recorder.clear();
        StepVerifier.create(support.operations().findById(Depot.class, eastId, graph))
                .assertNext(depot -> {
                    assertEquals(2, depot.getShipments().size(), "shipments(depth 1) 로드");
                    for (Shipment shipment : depot.getShipments()) {
                        Warehouse wh = shipment.getWarehouse();
                        assertNotNull(wh, "각 shipment 의 warehouse(depth 2) 로드");
                        assertNotNull(wh.getId());
                        assertEquals("US", wh.getId().getRegion());
                        assertEquals(1, wh.getId().getCode());
                        assertEquals("north-hub", wh.getTitle(),
                                "depth>1 subgraph 로 복합키 warehouse 가 stub 이 아닌 fully-loaded 여야 한다");
                    }
                })
                .verifyComplete();

        // root(depot) 1 + shipments IN 1 + warehouse 복합 배치 1 = 3. 각 레벨 SELECT 발생, lazy proxy 아님.
        assertEquals(3, recorder.selectCount(), "레벨당 쿼리 1회 — depth>1 복합키 leaf 여도 N+1 없음");
        assertEquals(1, recorder.selectCountFor("warehouse"),
                "복합키 부모는 shipment 수와 무관하게 한 번의 배치(OR-of-ANDs) 쿼리로 로드된다");
    }

    @Test
    void programmaticAddSubgraphFindAllLoadsCompositeWarehouseWithoutNPlusOne() {
        EntityGraph<Depot> graph = graphs.building(Depot.class)
                .addSubgraph("shipments")
                .addAttributeNodes("warehouse")
                .build();

        List<Depot> depots = new ArrayList<>();
        recorder.clear();
        StepVerifier.create(support.operations().findAll(Depot.class, graph))
                .recordWith(() -> depots)
                .expectNextCount(2)
                .verifyComplete();

        int totalShipments = 0;
        for (Depot depot : depots) {
            for (Shipment shipment : depot.getShipments()) {
                totalShipments++;
                Warehouse wh = shipment.getWarehouse();
                assertNotNull(wh.getTitle(),
                        "프로그램적 addSubgraph 로도 depth 2 복합키 warehouse 가 fully-load 된다");
                // 각 shipment 가 자기 FK 튜플에 맞는 warehouse 로 정확히 매칭돼야 한다(wrong-object 방지).
                if ("s3".equals(shipment.getCode())) {
                    assertEquals("KR", wh.getId().getRegion());
                    assertEquals("seoul-hub", wh.getTitle());
                } else {
                    assertEquals("US", wh.getId().getRegion());
                    assertEquals("north-hub", wh.getTitle());
                }
            }
        }
        assertEquals(3, totalShipments, "depot 2개가 총 3건 shipment");

        // root 1 + shipments IN 1 + warehouse 복합 배치 1 = 3. shipment/warehouse 수와 무관하게 고정(N+1 없음).
        assertEquals(3, recorder.selectCount(), "부모 수와 무관하게 depth 2 복합키 SELECT 고정");
        assertEquals(1, recorder.selectCountFor("warehouse"),
                "서로 다른 복합 튜플도 de-dup 후 한 번의 OR 확장 쿼리로 로드된다");
    }

    private Long depotId(String name) {
        return support.operations().findAll(Depot.class, io.nova.query.QuerySpec.empty())
                .filter(d -> d.getName().equals(name))
                .next()
                .map(Depot::getId)
                .block();
    }

    private static Warehouse warehouse(String region, Integer code, String title) {
        Warehouse warehouse = new Warehouse();
        warehouse.setId(new WarehouseKey(region, code));
        warehouse.setTitle(title);
        return warehouse;
    }

    private static Depot depot(String name) {
        Depot depot = new Depot();
        depot.setName(name);
        return depot;
    }

    private static Shipment shipment(String code, Depot depot, String region, Integer whCode) {
        Shipment shipment = new Shipment();
        shipment.setCode(code);
        shipment.setDepot(depot);
        Warehouse ref = new Warehouse();
        ref.setId(new WarehouseKey(region, whCode));
        shipment.setWarehouse(ref);
        return shipment;
    }

    /** 실행된 SQL 문장을 모아 총 SELECT 및 특정 테이블 SELECT 발행 횟수를 세는 listener. */
    private static final class RecordingListener implements SqlExecutionListener {
        private final List<String> statements = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void onBeforeExecution(SqlStatement statement) {
            statements.add(statement.sql());
        }

        void clear() {
            statements.clear();
        }

        long selectCount() {
            synchronized (statements) {
                return statements.stream()
                        .map(sql -> sql.toLowerCase())
                        .filter(sql -> sql.startsWith("select"))
                        .count();
            }
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

    // ------------------------------------------------------------------------------------
    // Fixtures — Depot 1─* Shipment *─1 Warehouse(@EmbeddedId) (2단계 그래프, 복합키 leaf)
    // ------------------------------------------------------------------------------------

    @Embeddable
    public static class WarehouseKey {
        @Column(name = "region")
        private String region;
        @Column(name = "code")
        private Integer code;

        public WarehouseKey() {
        }

        public WarehouseKey(String region, Integer code) {
            this.region = region;
            this.code = code;
        }

        public String getRegion() {
            return region;
        }

        public Integer getCode() {
            return code;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof WarehouseKey that
                    && Objects.equals(region, that.region) && Objects.equals(code, that.code);
        }

        @Override
        public int hashCode() {
            return Objects.hash(region, code);
        }
    }

    @Entity
    @Table(name = "warehouse")
    public static class Warehouse {
        @EmbeddedId
        private WarehouseKey id;
        @Column(name = "title")
        private String title;

        public Warehouse() {
        }

        public WarehouseKey getId() {
            return id;
        }

        public void setId(WarehouseKey id) {
            this.id = id;
        }

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }
    }

    @Entity
    @Table(name = "depot")
    @NamedEntityGraph(
            name = "Depot.shipmentsWarehouse",
            attributeNodes = @NamedAttributeNode(value = "shipments", subgraph = "shipmentWarehouse"),
            subgraphs = @NamedSubgraph(
                    name = "shipmentWarehouse", attributeNodes = @NamedAttributeNode("warehouse")))
    public static class Depot {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        @Column(name = "id")
        private Long id;
        @Column(name = "name")
        private String name;
        @OneToMany(targetEntity = Shipment.class, mappedBy = "depot")
        private List<Shipment> shipments;

        public Depot() {
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

        public List<Shipment> getShipments() {
            return shipments;
        }
    }

    @Entity
    @Table(name = "shipment")
    public static class Shipment {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        @Column(name = "id")
        private Long id;
        @Column(name = "code")
        private String code;
        @ManyToOne(targetEntity = Depot.class)
        @JoinColumn(name = "depot_id")
        private Depot depot;
        @ManyToOne(targetEntity = Warehouse.class)
        @JoinColumns({
                @JoinColumn(name = "wh_region", referencedColumnName = "region"),
                @JoinColumn(name = "wh_code", referencedColumnName = "code")
        })
        private Warehouse warehouse;

        public Shipment() {
        }

        public Long getId() {
            return id;
        }

        public String getCode() {
            return code;
        }

        public void setCode(String code) {
            this.code = code;
        }

        public Depot getDepot() {
            return depot;
        }

        public void setDepot(Depot depot) {
            this.depot = depot;
        }

        public Warehouse getWarehouse() {
            return warehouse;
        }

        public void setWarehouse(Warehouse warehouse) {
            this.warehouse = warehouse;
        }
    }
}
