package io.nova;

import io.nova.core.EntityStateDetector;
import io.nova.core.SimpleReactiveEntityOperations;
import io.nova.dialect.h2.H2Dialect;
import io.nova.metadata.DefaultNamingStrategy;
import io.nova.metadata.EntityMetadataFactory;
import io.nova.query.criteria.ReactiveCriteriaExecutor;
import io.nova.query.jpql.JpqlExecutor;
import io.nova.r2dbc.R2dbcSqlExecutor;
import io.nova.r2dbc.R2dbcTransactionManager;
import io.nova.schema.SchemaInitializer;
import io.nova.schema.SimpleSchemaInitializer;
import io.nova.sql.Dialect;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinColumns;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Root;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 복합키(@EmbeddedId) 엔티티를 참조하는 to-one 연관을 projection이 아닌 <b>WHERE 술어 위치</b>
 * (ordering-comparison {@code <}/{@code <=}/{@code >}/{@code >=}, {@code BETWEEN}, {@code IN})에서
 * 다중컬럼으로 전개해 실제 R2DBC H2 driver로 검증한다. SQL string 단위 테스트만으로는 lexicographic
 * OR-of-ANDs 전개가 driver에 수용되고 올바른 row만 반환하는지 알 수 없으므로 실데이터 라운드트립으로 확인한다.
 *
 * <p>컴포넌트 순서(order_no, region)는 canonical {@code ToOneForeignKey} 순서를 따르며, JPQL 문자열 쿼리는
 * ordering/BETWEEN/IN을 모두 표현할 수 있다. JPA typed Criteria는 엔티티 참조가 Comparable이 아니므로
 * ordering/BETWEEN을 표현할 수 없어 IN만 E2E로 검증한다(ordering/BETWEEN의 SQL은 단위 테스트가 커버).
 */
class CompositeToOneQueryH2IntegrationTest {

    private static final AtomicInteger DB_SEQ = new AtomicInteger();

    private record Wiring(SimpleReactiveEntityOperations operations, SchemaInitializer schema,
                          JpqlExecutor jpql, ReactiveCriteriaExecutor criteria) {
    }

    private Wiring wire() {
        int seq = DB_SEQ.incrementAndGet();
        ConnectionFactory cf = ConnectionFactories.get(
                "r2dbc:h2:mem:///c2oquery" + seq + "?options=DB_CLOSE_DELAY=-1");
        Dialect dialect = new H2Dialect();
        EntityMetadataFactory metadataFactory = new EntityMetadataFactory(new DefaultNamingStrategy());
        R2dbcSqlExecutor executor = new R2dbcSqlExecutor(cf, dialect);
        R2dbcTransactionManager txManager = new R2dbcTransactionManager(cf);
        SimpleReactiveEntityOperations operations = new SimpleReactiveEntityOperations(
                metadataFactory, dialect, executor, new EntityStateDetector(), txManager);
        SchemaInitializer schema = new SimpleSchemaInitializer(operations, metadataFactory, dialect);
        JpqlExecutor jpql = new JpqlExecutor(operations, dialect, metadataFactory,
                Shipment.class, Warehouse.class);
        ReactiveCriteriaExecutor criteria = new ReactiveCriteriaExecutor(operations, dialect, metadataFactory);
        return new Wiring(operations, schema, jpql, criteria);
    }

    /**
     * (10,"AA")=w1, (10,"BB")=w2, (20,"AA")=w3 세 개의 복합키 창고와, 각 창고를 참조하는 shipment s1..s3.
     * lexicographic 순서: (10,"AA") < (10,"BB") < (20,"AA").
     */
    private reactor.core.publisher.Mono<Void> seed(Wiring w) {
        Warehouse w1 = new Warehouse(new WarehouseKey(10L, "AA"), "w1");
        Warehouse w2 = new Warehouse(new WarehouseKey(10L, "BB"), "w2");
        Warehouse w3 = new Warehouse(new WarehouseKey(20L, "AA"), "w3");
        return w.schema().create(List.of(Warehouse.class, Shipment.class))
                .thenMany(Flux.concat(
                        w.operations().save(w1),
                        w.operations().save(w2),
                        w.operations().save(w3),
                        w.operations().save(new Shipment(w1)),
                        w.operations().save(new Shipment(w2)),
                        w.operations().save(new Shipment(w3))))
                .then();
    }

    @Test
    void jpqlOrderingComparisonReturnsLexicographicallyLessRows() {
        Wiring w = wire();
        // ref = (10,"BB"). c.warehouse < ref → only (10,"AA")=s1.
        Warehouse ref = new Warehouse(new WarehouseKey(10L, "BB"), null);
        StepVerifier.create(
                seed(w).thenMany(w.jpql()
                        .createQuery("SELECT c.id FROM Shipment c WHERE c.warehouse < :ref ORDER BY c.id", Long.class)
                        .setParameter("ref", ref)
                        .getResultList()))
                .expectNext(1L)
                .verifyComplete();
    }

    @Test
    void jpqlLessOrEqualIncludesTupleEqualRow() {
        Wiring w = wire();
        // ref = (10,"BB"). c.warehouse <= ref → (10,"AA")=s1 and (10,"BB")=s2.
        Warehouse ref = new Warehouse(new WarehouseKey(10L, "BB"), null);
        StepVerifier.create(
                seed(w).thenMany(w.jpql()
                        .createQuery("SELECT c.id FROM Shipment c WHERE c.warehouse <= :ref ORDER BY c.id", Long.class)
                        .setParameter("ref", ref)
                        .getResultList()))
                .expectNext(1L, 2L)
                .verifyComplete();
    }

    @Test
    void jpqlGreaterThanReturnsLexicographicallyGreaterRows() {
        Wiring w = wire();
        // ref = (10,"BB"). c.warehouse > ref → (20,"AA")=s3.
        Warehouse ref = new Warehouse(new WarehouseKey(10L, "BB"), null);
        StepVerifier.create(
                seed(w).thenMany(w.jpql()
                        .createQuery("SELECT c.id FROM Shipment c WHERE c.warehouse > :ref ORDER BY c.id", Long.class)
                        .setParameter("ref", ref)
                        .getResultList()))
                .expectNext(3L)
                .verifyComplete();
    }

    @Test
    void jpqlBetweenReturnsRowsInInclusiveLexicographicRange() {
        Wiring w = wire();
        // BETWEEN (10,"AA") AND (10,"BB") → s1 and s2 (inclusive), not (20,"AA").
        Warehouse lo = new Warehouse(new WarehouseKey(10L, "AA"), null);
        Warehouse hi = new Warehouse(new WarehouseKey(10L, "BB"), null);
        StepVerifier.create(
                seed(w).thenMany(w.jpql()
                        .createQuery("SELECT c.id FROM Shipment c WHERE c.warehouse BETWEEN :lo AND :hi ORDER BY c.id",
                                Long.class)
                        .setParameter("lo", lo)
                        .setParameter("hi", hi)
                        .getResultList()))
                .expectNext(1L, 2L)
                .verifyComplete();
    }

    @Test
    void jpqlInReturnsRowsMatchingAnyReferenceTuple() {
        Wiring w = wire();
        // IN ((10,"AA"),(20,"AA")) → s1 and s3, not (10,"BB").
        Warehouse a = new Warehouse(new WarehouseKey(10L, "AA"), null);
        Warehouse b = new Warehouse(new WarehouseKey(20L, "AA"), null);
        StepVerifier.create(
                seed(w).thenMany(w.jpql()
                        .createQuery("SELECT c.id FROM Shipment c WHERE c.warehouse IN (:a, :b) ORDER BY c.id",
                                Long.class)
                        .setParameter("a", a)
                        .setParameter("b", b)
                        .getResultList()))
                .expectNext(1L, 3L)
                .verifyComplete();
    }

    @Test
    void jpqlGreaterOrEqualIncludesTupleEqualRow() {
        Wiring w = wire();
        // ref = (10,"BB"). c.warehouse >= ref → (10,"BB")=s2 and (20,"AA")=s3, not (10,"AA").
        Warehouse ref = new Warehouse(new WarehouseKey(10L, "BB"), null);
        StepVerifier.create(
                seed(w).thenMany(w.jpql()
                        .createQuery("SELECT c.id FROM Shipment c WHERE c.warehouse >= :ref ORDER BY c.id", Long.class)
                        .setParameter("ref", ref)
                        .getResultList()))
                .expectNext(2L, 3L)
                .verifyComplete();
    }

    @Test
    void jpqlNotInExcludesMatchingReferenceTuples() {
        Wiring w = wire();
        // NOT IN ((10,"AA"),(20,"AA")) → only (10,"BB")=s2.
        Warehouse a = new Warehouse(new WarehouseKey(10L, "AA"), null);
        Warehouse b = new Warehouse(new WarehouseKey(20L, "AA"), null);
        StepVerifier.create(
                seed(w).thenMany(w.jpql()
                        .createQuery("SELECT c.id FROM Shipment c WHERE c.warehouse NOT IN (:a, :b) ORDER BY c.id",
                                Long.class)
                        .setParameter("a", a)
                        .setParameter("b", b)
                        .getResultList()))
                .expectNext(2L)
                .verifyComplete();
    }

    @Test
    void entityReturningJpqlRejectsCompositeToOneInWhereLoudly() {
        // 엔티티 반환(join 없음) 경로는 JpqlEntityQueryPlanner → 코어 렌더러로 가며, 복합 to-one을 첫 FK 컬럼
        // 하나로만 비교하는 silent wrong-row가 되므로 build-time에 명확히 거부돼야 한다(silent 대신 loud).
        Wiring w = wire();
        Warehouse ref = new Warehouse(new WarehouseKey(10L, "BB"), null);
        StepVerifier.create(
                seed(w).thenMany(w.jpql()
                        .createQuery("SELECT c FROM Shipment c WHERE c.warehouse < :ref", Shipment.class)
                        .setParameter("ref", ref)
                        .getResultList()))
                .verifyErrorMatches(error -> error instanceof io.nova.query.jpql.JpqlException
                        && error.getMessage().contains("Composite-key to-one")
                        && error.getMessage().contains("entity-returning"));
    }

    @Test
    void criteriaInOverCompositeToOneReturnsMatchingEntities() {
        Wiring w = wire();
        Warehouse a = new Warehouse(new WarehouseKey(10L, "AA"), null);
        Warehouse b = new Warehouse(new WarehouseKey(20L, "AA"), null);
        CriteriaBuilder cb = w.criteria().getCriteriaBuilder();
        CriteriaQuery<Shipment> cq = cb.createQuery(Shipment.class);
        Root<Shipment> c = cq.from(Shipment.class);
        cq.where(cb.in(c.get("warehouse")).value(a).value(b));

        StepVerifier.create(
                seed(w).thenMany(w.criteria().createQuery(cq).getResultList())
                        .map(Shipment::getId)
                        .sort())
                .expectNext(1L, 3L)
                .verifyComplete();
    }

    // ============================== fixtures ==============================

    @Embeddable
    public static class WarehouseKey {
        @Column(name = "wh_no")
        private Long whNo;
        @Column(name = "region")
        private String region;

        public WarehouseKey() {
        }

        public WarehouseKey(Long whNo, String region) {
            this.whNo = whNo;
            this.region = region;
        }
    }

    @Entity(name = "Warehouse")
    @Table(name = "c2o_warehouse")
    public static class Warehouse {
        @EmbeddedId
        private WarehouseKey id;
        @Column(name = "label")
        private String label;

        public Warehouse() {
        }

        public Warehouse(WarehouseKey id, String label) {
            this.id = id;
            this.label = label;
        }
    }

    @Entity(name = "Shipment")
    @Table(name = "c2o_shipment")
    public static class Shipment {
        // IDENTITY로 삽입 순서대로 1,2,3을 부여받는다(EntityStateDetector.isNew는 id==null만 신규로 본다).
        @Id
        @jakarta.persistence.GeneratedValue(strategy = jakarta.persistence.GenerationType.IDENTITY)
        @Column(name = "id")
        private Long id;

        @ManyToOne(targetEntity = Warehouse.class)
        @JoinColumns({
                @JoinColumn(name = "wh_no_fk", referencedColumnName = "wh_no"),
                @JoinColumn(name = "region_fk", referencedColumnName = "region")
        })
        private Warehouse warehouse;

        public Shipment() {
        }

        public Shipment(Warehouse warehouse) {
            this.warehouse = warehouse;
        }

        public Long getId() {
            return id;
        }
    }
}
