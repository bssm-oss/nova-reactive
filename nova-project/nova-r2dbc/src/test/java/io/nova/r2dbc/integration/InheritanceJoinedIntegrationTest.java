package io.nova.r2dbc.integration;

import jakarta.persistence.DiscriminatorColumn;
import jakarta.persistence.DiscriminatorType;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Inheritance;
import jakarta.persistence.InheritanceType;
import jakarta.persistence.Table;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Root;
import io.nova.query.QuerySpec;
import io.nova.query.criteria.ReactiveCriteriaExecutor;
import io.nova.query.jpql.JpqlExecutor;
import io.nova.schema.SchemaInitializer;
import io.nova.schema.SimpleSchemaInitializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code @Inheritance(JOINED)}가 H2 in-memory R2DBC driver와 end-to-end로 동작하는지 검증한다.
 * 멀티테이블 INSERT(root→subtype), JOIN 다형 SELECT, 멀티테이블 UPDATE/DELETE를 풀 라운드트립으로 본다.
 * SQL string unit test만으로는 driver 수용성(JOIN 디코딩/FK 순서/생성 키 회수)을 보장할 수 없으므로 필수다.
 */
class InheritanceJoinedIntegrationTest {
    private H2IntegrationTestSupport support;
    private JpqlExecutor jpql;
    private ReactiveCriteriaExecutor criteria;

    @BeforeEach
    void setUp() {
        support = H2IntegrationTestSupport.create();
        SchemaInitializer schema =
                new SimpleSchemaInitializer(support.operations(), support.metadataFactory(), support.dialect());
        schema.create(JVehicle.class, JCar.class, JTruck.class).block();
        jpql = new JpqlExecutor(support.operations(), support.dialect(), support.metadataFactory(),
                JVehicle.class, JCar.class, JTruck.class);
        criteria = new ReactiveCriteriaExecutor(support.operations(), support.dialect(), support.metadataFactory());
    }

    @Test
    void subtypeSaveThenPolymorphicFindByIdReturnsConcreteType() {
        Mono<JVehicle> pipeline = support.operations().save(new JCar("ada", 4))
                .flatMap(saved -> support.operations().findById(JVehicle.class, saved.getId()));
        StepVerifier.create(pipeline)
                .assertNext(vehicle -> {
                    JCar found = assertInstanceOf(JCar.class, vehicle, "CAR row는 JCar로 매핑돼야 한다");
                    assertEquals("ada", found.getName());
                    assertEquals(4, found.getDoors());
                })
                .verifyComplete();

        Mono<JVehicle> truck = support.operations().save(new JTruck("ben", 12.5))
                .flatMap(saved -> support.operations().findById(JVehicle.class, saved.getId()));
        StepVerifier.create(truck)
                .assertNext(vehicle -> {
                    JTruck found = assertInstanceOf(JTruck.class, vehicle);
                    assertEquals(12.5, found.getPayload());
                    assertEquals("ben", found.getName());
                })
                .verifyComplete();
    }

    @Test
    void polymorphicFindAllReturnsMixedConcreteTypes() {
        support.operations().save(new JCar("ada", 4))
                .then(support.operations().save(new JTruck("ben", 12.5)))
                .then(support.operations().save(new JCar("cyd", 2)))
                .block();

        List<JVehicle> result = new ArrayList<>();
        StepVerifier.create(support.operations().findAll(JVehicle.class, QuerySpec.empty()))
                .recordWith(() -> result)
                .expectNextCount(3)
                .verifyComplete();

        long cars = result.stream().filter(v -> v instanceof JCar).count();
        long trucks = result.stream().filter(v -> v instanceof JTruck).count();
        assertEquals(2, cars);
        assertEquals(1, trucks);
    }

    @Test
    void polymorphicFindAllWithPredicateOnRootColumnFiltersWithoutAmbiguousColumn() {
        // C1 회귀: 루트 타입 다형 findAll에 WHERE/정렬을 걸면 과거엔 id가 모든 서브타입 테이블에 존재해
        // ambiguous-column DB 에러가 났다. 파생 테이블 wrapping으로 정상 동작해야 한다.
        Long carId = support.operations().save(new JCar("ada", 4)).map(JVehicle::getId).block();
        support.operations().save(new JTruck("ben", 12.5)).block();

        List<JVehicle> result = new ArrayList<>();
        StepVerifier.create(support.operations().findAll(
                        JVehicle.class, QuerySpec.empty().where(io.nova.query.Criteria.eq("id", carId))))
                .recordWith(() -> result)
                .expectNextCount(1)
                .verifyComplete();
        assertEquals(carId, result.get(0).getId());
        assertEquals(JCar.class, result.get(0).getClass());
    }

    @Test
    void concreteTypeFindAllReturnsOnlyThatSubtype() {
        support.operations().save(new JCar("ada", 4))
                .then(support.operations().save(new JTruck("ben", 12.5)))
                .block();

        List<JCar> cars = new ArrayList<>();
        StepVerifier.create(support.operations().findAll(JCar.class, QuerySpec.empty()))
                .recordWith(() -> cars)
                .expectNextCount(1)
                .verifyComplete();
        assertEquals(4, cars.get(0).getDoors());
    }

    @Test
    void updatePersistsRootAndSubtypeColumns() {
        JCar saved = (JCar) support.operations().save(new JCar("ada", 4)).block();
        saved.setName("ada-renamed");
        saved.setDoors(5);

        Mono<JVehicle> pipeline = support.operations().save(saved)
                .flatMap(updated -> support.operations().findById(JVehicle.class, saved.getId()));
        StepVerifier.create(pipeline)
                .assertNext(vehicle -> {
                    JCar found = assertInstanceOf(JCar.class, vehicle);
                    assertEquals("ada-renamed", found.getName(), "루트 테이블 컬럼이 갱신돼야 한다");
                    assertEquals(5, found.getDoors(), "서브타입 테이블 컬럼이 갱신돼야 한다");
                })
                .verifyComplete();
    }

    @Test
    void deleteRemovesRootAndSubtypeRows() {
        JCar saved = (JCar) support.operations().save(new JCar("ada", 4)).block();

        StepVerifier.create(support.operations().delete(saved))
                .expectNextCount(1)
                .verifyComplete();

        StepVerifier.create(support.operations().findById(JVehicle.class, saved.getId()))
                .verifyComplete();
    }

    // --- TYPE() / TREAT() over the JOINED double-nested derived table ------

    @Test
    void jpqlEntitySelectWithTypeEqualsReturnsOnlyJoinedConcreteSubtype() {
        support.operations().save(new JCar("ada", 4))
                .then(support.operations().save(new JTruck("ben", 12.5)))
                .then(support.operations().save(new JCar("cyd", 2)))
                .block();

        List<JVehicle> result = new ArrayList<>();
        StepVerifier.create(
                        jpql.createQuery("SELECT e FROM JVehicle e WHERE TYPE(e) = JCar ORDER BY e.name", JVehicle.class)
                                .getResultList())
                .recordWith(() -> result)
                .expectNextCount(2)
                .verifyComplete();
        assertTrue(result.stream().allMatch(v -> v instanceof JCar), "TYPE(e) = JCar는 JCar row만 반환해야 한다");
        assertEquals(List.of("ada", "cyd"), result.stream().map(JVehicle::getName).toList());
    }

    @Test
    void jpqlScalarTreatProjectsJoinedSubtypeAttributeExcludingNonMatchingRows() {
        // TREAT(e AS JCar).doors는 JOINED 파생 테이블의 discriminator=CAR 제약에 의존한다 — 이 제약이 없으면
        // JTruck 행에서 doors는 (매칭되지 않는 j_car LEFT JOIN이라) NULL이라 조용히 null-row가 섞일 수 있다.
        support.operations().save(new JCar("ada", 4))
                .then(support.operations().save(new JTruck("ben", 12.5)))
                .then(support.operations().save(new JCar("cyd", 2)))
                .block();

        StepVerifier.create(
                        jpql.createQuery("SELECT TREAT(e AS JCar).doors FROM JVehicle e ORDER BY e.name", Object.class)
                                .getResultList())
                .assertNext(v -> assertEquals(4, ((Number) v).intValue()))
                .assertNext(v -> assertEquals(2, ((Number) v).intValue()))
                .verifyComplete();
    }

    @Test
    void criteriaEntitySelectWithTypeEqualsReturnsOnlyJoinedConcreteSubtype() {
        support.operations().save(new JCar("ada", 4))
                .then(support.operations().save(new JTruck("ben", 12.5)))
                .block();

        CriteriaBuilder cb = criteria.getCriteriaBuilder();
        CriteriaQuery<JVehicle> cq = cb.createQuery(JVehicle.class);
        Root<JVehicle> e = cq.from(JVehicle.class);
        cq.select(e).where(cb.equal(e.type(), JCar.class)).orderBy(cb.asc(e.<String>get("name")));

        StepVerifier.create(criteria.createQuery(cq).getResultList())
                .assertNext(v -> assertEquals("ada", assertInstanceOf(JCar.class, v).getName()))
                .verifyComplete();
    }

    @Test
    void criteriaScalarTreatProjectsJoinedSubtypeAttribute() {
        support.operations().save(new JCar("ada", 4))
                .then(support.operations().save(new JTruck("ben", 12.5)))
                .block();

        CriteriaBuilder cb = criteria.getCriteriaBuilder();
        CriteriaQuery<Integer> cq = cb.createQuery(Integer.class);
        Root<JVehicle> e = cq.from(JVehicle.class);
        Root<JCar> car = cb.treat(e, JCar.class);
        cq.select(car.<Integer>get("doors")).orderBy(cb.asc(e.<String>get("name")));

        StepVerifier.create(criteria.createQuery(cq).getResultList())
                .assertNext(v -> assertEquals(4, ((Number) v).intValue()))
                .verifyComplete();
    }

    // --- fixtures ----------------------------------------------------------

    @Entity
    @Table(name = "j_vehicle")
    @Inheritance(strategy = InheritanceType.JOINED)
    @DiscriminatorColumn(name = "kind", discriminatorType = DiscriminatorType.STRING)
    abstract static class JVehicle {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;
        private String name;

        JVehicle() {
        }

        JVehicle(String name) {
            this.name = name;
        }

        Long getId() {
            return id;
        }

        String getName() {
            return name;
        }

        void setName(String name) {
            this.name = name;
        }
    }

    @Entity
    @Table(name = "j_car")
    @DiscriminatorValue("CAR")
    static class JCar extends JVehicle {
        private int doors;

        JCar() {
        }

        JCar(String name, int doors) {
            super(name);
            this.doors = doors;
        }

        int getDoors() {
            return doors;
        }

        void setDoors(int doors) {
            this.doors = doors;
        }
    }

    @Entity
    @Table(name = "j_truck")
    @DiscriminatorValue("TRUCK")
    static class JTruck extends JVehicle {
        private double payload;

        JTruck() {
        }

        JTruck(String name, double payload) {
            super(name);
            this.payload = payload;
        }

        double getPayload() {
            return payload;
        }
    }
}
