package io.nova;

import io.nova.core.ReactiveEntityManager;
import io.nova.core.ReactiveEntityOperations;
import io.nova.query.NativeQuery;
import io.nova.schema.SchemaInitializer;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinColumns;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.io.Serializable;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 복합키(@EmbeddedId / @IdClass) 엔티티를 참조하는 to-one 관계(@ManyToOne / owning @OneToOne)의 다중컬럼 FK를
 * 실제 R2DBC H2 driver로 검증한다. SQL string 단위 테스트만으로는 N개 FK 컬럼 DDL이 driver에 수용되는지,
 * save가 참조 엔티티의 각 @Id 컴포넌트를 올바른 컬럼에 바인딩하는지, findById가 그 컬럼들을 읽어 복합 id를 가진
 * 참조 stub을 정확한 순서로 복원하는지 알 수 없다 — ddl-auto 라운드트립으로 확인한다. 데이터베이스는 메모리
 * H2(독립 DB per test).
 */
class ManyToOneCompositeKeyH2IntegrationTest {

    private static final AtomicInteger DB_SEQ = new AtomicInteger();

    private ConnectionFactory freshConnectionFactory() {
        int seq = DB_SEQ.incrementAndGet();
        return ConnectionFactories.get(
                "r2dbc:h2:mem:///m2ocomposite" + seq + "?options=DB_CLOSE_DELAY=-1");
    }

    // ---------------------------------------------------------------------------------------------
    // @EmbeddedId 타겟 @ManyToOne 라운드트립 + 2컬럼 DDL 확인
    // ---------------------------------------------------------------------------------------------

    @Test
    void embeddedIdTargetManyToOneRoundTrip() {
        ConnectionFactory cf = freshConnectionFactory();
        SchemaInitializer schema = Nova.schemaInitializer(cf);
        ReactiveEntityOperations operations = Nova.create(cf);

        OrderEntity order = new OrderEntity(new OrderKey(100L, "US"), "first");
        Line line = new Line(order);

        StepVerifier.create(
                schema.create(List.of(OrderEntity.class, Line.class))
                        .then(operations.save(order))
                        .then(operations.save(line))
                        .flatMap(saved -> operations.findById(Line.class, saved.getId()))
        ).assertNext(loaded -> {
            org.junit.jupiter.api.Assertions.assertNotNull(loaded.order, "composite FK should restore reference stub");
            org.junit.jupiter.api.Assertions.assertNotNull(loaded.order.id);
            org.junit.jupiter.api.Assertions.assertEquals(100L, loaded.order.id.orderNo);
            org.junit.jupiter.api.Assertions.assertEquals("US", loaded.order.id.region);
        }).verifyComplete();

        // 두 개의 FK 컬럼이 실제로 존재하고 컴포넌트 값이 올바른 컬럼에 저장됐는지 raw SELECT로 확인한다.
        StepVerifier.create(operations.queryNativeOne(
                NativeQuery.of("select \"order_no_fk\" as a, \"region_fk\" as b from \"mco_line\" where \"id\" = 1"),
                row -> row.get("a", Long.class) + "/" + row.get("b", String.class)))
                .expectNext("100/US").verifyComplete();
    }

    // ---------------------------------------------------------------------------------------------
    // @IdClass 타겟 @ManyToOne 라운드트립
    // ---------------------------------------------------------------------------------------------

    @Test
    void idClassTargetManyToOneRoundTrip() {
        ConnectionFactory cf = freshConnectionFactory();
        SchemaInitializer schema = Nova.schemaInitializer(cf);
        ReactiveEntityOperations operations = Nova.create(cf);

        IdcOrder order = new IdcOrder(200L, "EU", "second");
        IdcLine line = new IdcLine(order);

        StepVerifier.create(
                schema.create(List.of(IdcOrder.class, IdcLine.class))
                        .then(operations.save(order))
                        .then(operations.save(line))
                        .flatMap(saved -> operations.findById(IdcLine.class, saved.getId()))
        ).assertNext(loaded -> {
            org.junit.jupiter.api.Assertions.assertNotNull(loaded.order, "composite @IdClass FK should restore stub");
            org.junit.jupiter.api.Assertions.assertEquals(200L, loaded.order.orderNo);
            org.junit.jupiter.api.Assertions.assertEquals("EU", loaded.order.region);
        }).verifyComplete();

        StepVerifier.create(operations.queryNativeOne(
                NativeQuery.of("select \"order_no_fk\" as a, \"region_fk\" as b from \"mco_idc_line\" where \"id\" = 1"),
                row -> row.get("a", Long.class) + "/" + row.get("b", String.class)))
                .expectNext("200/EU").verifyComplete();
    }

    // ---------------------------------------------------------------------------------------------
    // 비-Long(String + UUID) 컴포넌트 저장타입 정렬/디코드 증명
    // ---------------------------------------------------------------------------------------------

    @Test
    void nonLongComponentTargetRoundTrip() {
        ConnectionFactory cf = freshConnectionFactory();
        SchemaInitializer schema = Nova.schemaInitializer(cf);
        ReactiveEntityOperations operations = Nova.create(cf);

        UUID token = UUID.fromString("11111111-2222-3333-4444-555555555555");
        Account account = new Account(new AccountKey("ACME", token), "gold");
        Ledger ledger = new Ledger(account);

        StepVerifier.create(
                schema.create(List.of(Account.class, Ledger.class))
                        .then(operations.save(account))
                        .then(operations.save(ledger))
                        .flatMap(saved -> operations.findById(Ledger.class, saved.getId()))
        ).assertNext(loaded -> {
            org.junit.jupiter.api.Assertions.assertNotNull(loaded.account);
            org.junit.jupiter.api.Assertions.assertEquals("ACME", loaded.account.id.code);
            org.junit.jupiter.api.Assertions.assertEquals(token, loaded.account.id.token);
        }).verifyComplete();

        // UUID 컴포넌트는 varchar로 저장된다(read-source-type: driver가 varchar→UUID 직접 디코드 불가).
        StepVerifier.create(operations.queryNativeOne(
                NativeQuery.of("select \"code_fk\" as a, \"token_fk\" as b from \"mco_ledger\" where \"id\" = 1"),
                row -> row.get("a", String.class) + "/" + row.get("b", String.class)))
                .expectNext("ACME/" + token).verifyComplete();
    }

    // ---------------------------------------------------------------------------------------------
    // owning @OneToOne 이 복합키 타겟을 참조
    // ---------------------------------------------------------------------------------------------

    @Test
    void owningOneToOneCompositeTargetRoundTrip() {
        ConnectionFactory cf = freshConnectionFactory();
        SchemaInitializer schema = Nova.schemaInitializer(cf);
        ReactiveEntityOperations operations = Nova.create(cf);

        Passport passport = new Passport(new PassportKey(1L, "KR"), "P123");
        Person person = new Person(passport);

        StepVerifier.create(
                schema.create(List.of(Passport.class, Person.class))
                        .then(operations.save(passport))
                        .then(operations.save(person))
                        .flatMap(saved -> operations.findById(Person.class, saved.getId()))
        ).assertNext(loaded -> {
            org.junit.jupiter.api.Assertions.assertNotNull(loaded.passport);
            org.junit.jupiter.api.Assertions.assertEquals(1L, loaded.passport.id.serial);
            org.junit.jupiter.api.Assertions.assertEquals("KR", loaded.passport.id.country);
        }).verifyComplete();
    }

    // ---------------------------------------------------------------------------------------------
    // EntityManager merge + session persist 가 복합 to-one 엔티티에서 깨지지 않고(clean) 참조를 보존
    // ---------------------------------------------------------------------------------------------

    @Test
    void entityManagerMergeAndSessionHandleCompositeToOneCleanly() {
        ConnectionFactory cf = freshConnectionFactory();
        SchemaInitializer schema = Nova.schemaInitializer(cf);
        ReactiveEntityManager em = Nova.entityManager(cf);

        OrderEntity order = new OrderEntity(new OrderKey(700L, "BR"), "m");
        Line line = new Line(order);

        // persist(session buildSnapshot가 복합 FK를 skip), merge(copyColumnState가 참조를 통째로 복사)가
        // 예전처럼 "has no @Id field"로 throw하지 않고 clean하게 동작하며, 이후 find가 복합 id stub을 복원한다.
        StepVerifier.create(
                schema.create(List.of(OrderEntity.class, Line.class))
                        .then(em.persist(order))
                        .then(em.persist(line))
                        .flatMap(saved -> {
                            Line changed = new Line(order);
                            changed.setId(saved.getId());
                            return em.merge(changed).thenReturn(saved.getId());
                        })
                        .flatMap(id -> em.find(Line.class, id))
        ).assertNext(found -> {
            org.junit.jupiter.api.Assertions.assertNotNull(found.order, "merge/session must preserve composite reference");
            org.junit.jupiter.api.Assertions.assertEquals(700L, found.order.id.orderNo);
            org.junit.jupiter.api.Assertions.assertEquals("BR", found.order.id.region);
        }).verifyComplete();
    }

    // ---------------------------------------------------------------------------------------------
    // @JoinColumns(foreignKey=CONSTRAINT) 복합 FK 제약이 위반 INSERT를 거부
    // ---------------------------------------------------------------------------------------------

    @Test
    void compositeForeignKeyConstraintRejectsViolatingInsert() {
        ConnectionFactory cf = freshConnectionFactory();
        SchemaInitializer schema = Nova.schemaInitializer(cf);
        ReactiveEntityOperations operations = Nova.create(cf);

        // 존재하지 않는 (order_no_fk, region_fk) 조합을 참조하는 child INSERT → 복합 FK 위반으로 거부.
        StepVerifier.create(
                schema.create(List.of(OrderEntity.class, ConstrainedLine.class))
                        .then(operations.executeNative(NativeQuery.of(
                                "insert into \"mco_constrained_line\" (\"id\", \"order_no_fk\", \"region_fk\")"
                                        + " values (1, 999, 'ZZ')")))
        ).verifyError();
    }

    @Test
    void compositeForeignKeyConstraintAllowsValidInsert() {
        ConnectionFactory cf = freshConnectionFactory();
        SchemaInitializer schema = Nova.schemaInitializer(cf);
        ReactiveEntityOperations operations = Nova.create(cf);

        OrderEntity order = new OrderEntity(new OrderKey(500L, "JP"), "ok");
        StepVerifier.create(
                schema.create(List.of(OrderEntity.class, ConstrainedLine.class))
                        .then(operations.save(order))
                        .then(operations.executeNative(NativeQuery.of(
                                "insert into \"mco_constrained_line\" (\"id\", \"order_no_fk\", \"region_fk\")"
                                        + " values (1, 500, 'JP')")))
        ).expectNextCount(1).verifyComplete();
    }

    // ============================== fixtures: @EmbeddedId target ==================================

    @Embeddable
    public static class OrderKey {
        @Column(name = "order_no")
        private Long orderNo;
        @Column(name = "region")
        private String region;

        public OrderKey() {
        }

        public OrderKey(Long orderNo, String region) {
            this.orderNo = orderNo;
            this.region = region;
        }
    }

    @Entity
    @Table(name = "mco_order")
    public static class OrderEntity {
        @EmbeddedId
        private OrderKey id;
        @Column(name = "label")
        private String label;

        public OrderEntity() {
        }

        public OrderEntity(OrderKey id, String label) {
            this.id = id;
            this.label = label;
        }
    }

    @Entity
    @Table(name = "mco_line")
    public static class Line {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        @Column(name = "id")
        private Long id;

        @ManyToOne(targetEntity = OrderEntity.class)
        @JoinColumns({
                @JoinColumn(name = "order_no_fk", referencedColumnName = "order_no"),
                @JoinColumn(name = "region_fk", referencedColumnName = "region")
        })
        private OrderEntity order;

        public Line() {
        }

        public Line(OrderEntity order) {
            this.order = order;
        }

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }
    }

    @Entity
    @Table(name = "mco_constrained_line")
    public static class ConstrainedLine {
        @Id
        @Column(name = "id")
        private Long id;

        @ManyToOne(targetEntity = OrderEntity.class)
        @JoinColumns(
                value = {
                        @JoinColumn(name = "order_no_fk", referencedColumnName = "order_no"),
                        @JoinColumn(name = "region_fk", referencedColumnName = "region")
                },
                foreignKey = @ForeignKey(name = "fk_constrained_to_order"))
        private OrderEntity order;

        public ConstrainedLine() {
        }
    }

    // ============================== fixtures: @IdClass target =====================================

    public static class OrderIdClass implements Serializable {
        private Long orderNo;
        private String region;

        public OrderIdClass() {
        }

        public OrderIdClass(Long orderNo, String region) {
            this.orderNo = orderNo;
            this.region = region;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof OrderIdClass that)) {
                return false;
            }
            return Objects.equals(orderNo, that.orderNo) && Objects.equals(region, that.region);
        }

        @Override
        public int hashCode() {
            return Objects.hash(orderNo, region);
        }
    }

    @Entity
    @Table(name = "mco_idc_order")
    @IdClass(OrderIdClass.class)
    public static class IdcOrder {
        @Id
        @Column(name = "order_no")
        private Long orderNo;
        @Id
        @Column(name = "region")
        private String region;
        @Column(name = "label")
        private String label;

        public IdcOrder() {
        }

        public IdcOrder(Long orderNo, String region, String label) {
            this.orderNo = orderNo;
            this.region = region;
            this.label = label;
        }
    }

    @Entity
    @Table(name = "mco_idc_line")
    public static class IdcLine {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        @Column(name = "id")
        private Long id;

        @ManyToOne(targetEntity = IdcOrder.class)
        @JoinColumns({
                @JoinColumn(name = "order_no_fk", referencedColumnName = "order_no"),
                @JoinColumn(name = "region_fk", referencedColumnName = "region")
        })
        private IdcOrder order;

        public IdcLine() {
        }

        public IdcLine(IdcOrder order) {
            this.order = order;
        }

        public Long getId() {
            return id;
        }
    }

    // ============================== fixtures: non-Long components =================================

    @Embeddable
    public static class AccountKey {
        @Column(name = "code")
        private String code;
        @Column(name = "token")
        private UUID token;

        public AccountKey() {
        }

        public AccountKey(String code, UUID token) {
            this.code = code;
            this.token = token;
        }
    }

    @Entity
    @Table(name = "mco_account")
    public static class Account {
        @EmbeddedId
        private AccountKey id;
        @Column(name = "tier")
        private String tier;

        public Account() {
        }

        public Account(AccountKey id, String tier) {
            this.id = id;
            this.tier = tier;
        }
    }

    @Entity
    @Table(name = "mco_ledger")
    public static class Ledger {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        @Column(name = "id")
        private Long id;

        @ManyToOne(targetEntity = Account.class)
        @JoinColumns({
                @JoinColumn(name = "code_fk", referencedColumnName = "code"),
                @JoinColumn(name = "token_fk", referencedColumnName = "token")
        })
        private Account account;

        public Ledger() {
        }

        public Ledger(Account account) {
            this.account = account;
        }

        public Long getId() {
            return id;
        }
    }

    // ============================== fixtures: owning @OneToOne ====================================

    @Embeddable
    public static class PassportKey {
        @Column(name = "serial")
        private Long serial;
        @Column(name = "country")
        private String country;

        public PassportKey() {
        }

        public PassportKey(Long serial, String country) {
            this.serial = serial;
            this.country = country;
        }
    }

    @Entity
    @Table(name = "mco_passport")
    public static class Passport {
        @EmbeddedId
        private PassportKey id;
        @Column(name = "number")
        private String number;

        public Passport() {
        }

        public Passport(PassportKey id, String number) {
            this.id = id;
            this.number = number;
        }
    }

    @Entity
    @Table(name = "mco_person")
    public static class Person {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        @Column(name = "id")
        private Long id;

        @OneToOne(targetEntity = Passport.class)
        @JoinColumns({
                @JoinColumn(name = "serial_fk", referencedColumnName = "serial"),
                @JoinColumn(name = "country_fk", referencedColumnName = "country")
        })
        private Passport passport;

        public Person() {
        }

        public Person(Passport passport) {
            this.passport = passport;
        }

        public Long getId() {
            return id;
        }
    }
}
