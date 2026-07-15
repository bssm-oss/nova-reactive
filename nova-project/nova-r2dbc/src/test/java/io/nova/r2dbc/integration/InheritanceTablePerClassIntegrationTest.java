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
import jakarta.persistence.TableGenerator;
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
 * {@code @Inheritance(TABLE_PER_CLASS)}가 H2 in-memory R2DBC driver와 end-to-end로 동작하는지 검증한다.
 * 각 구체 서브타입은 독립 테이블을 가지며, 다형 조회는 UNION ALL로 합쳐진다. 식별자는 모든 테이블이 공유하는
 * {@code @TableGenerator}로 전역 고유성을 보장한다(IDENTITY+TPC 다형 충돌 회피).
 */
class InheritanceTablePerClassIntegrationTest {
    private H2IntegrationTestSupport support;
    private JpqlExecutor jpql;
    private ReactiveCriteriaExecutor criteria;

    @BeforeEach
    void setUp() {
        support = H2IntegrationTestSupport.create();
        SchemaInitializer schema =
                new SimpleSchemaInitializer(support.operations(), support.metadataFactory(), support.dialect());
        schema.create(TVehicle.class, TCar.class, TTruck.class).block();
        jpql = new JpqlExecutor(support.operations(), support.dialect(), support.metadataFactory(),
                TVehicle.class, TCar.class, TTruck.class);
        criteria = new ReactiveCriteriaExecutor(support.operations(), support.dialect(), support.metadataFactory());
    }

    @Test
    void subtypeSaveThenPolymorphicFindByIdReturnsConcreteType() {
        Mono<TVehicle> pipeline = support.operations().save(new TCar("ada", 4))
                .flatMap(saved -> support.operations().findById(TVehicle.class, saved.getId()));
        StepVerifier.create(pipeline)
                .assertNext(vehicle -> {
                    TCar found = assertInstanceOf(TCar.class, vehicle, "CAR row는 TCar로 매핑돼야 한다");
                    assertEquals("ada", found.getName());
                    assertEquals(4, found.getDoors());
                })
                .verifyComplete();

        Mono<TVehicle> truck = support.operations().save(new TTruck("ben", 12.5))
                .flatMap(saved -> support.operations().findById(TVehicle.class, saved.getId()));
        StepVerifier.create(truck)
                .assertNext(vehicle -> {
                    TTruck found = assertInstanceOf(TTruck.class, vehicle);
                    assertEquals(12.5, found.getPayload());
                    assertEquals("ben", found.getName());
                })
                .verifyComplete();
    }

    @Test
    void polymorphicFindAllUnionsMixedConcreteTypes() {
        support.operations().save(new TCar("ada", 4))
                .then(support.operations().save(new TTruck("ben", 12.5)))
                .then(support.operations().save(new TCar("cyd", 2)))
                .block();

        List<TVehicle> result = new ArrayList<>();
        StepVerifier.create(support.operations().findAll(TVehicle.class, QuerySpec.empty()))
                .recordWith(() -> result)
                .expectNextCount(3)
                .verifyComplete();

        long cars = result.stream().filter(v -> v instanceof TCar).count();
        long trucks = result.stream().filter(v -> v instanceof TTruck).count();
        assertEquals(2, cars);
        assertEquals(1, trucks);
    }

    @Test
    void concreteTypeFindAllQueriesSingleTable() {
        support.operations().save(new TCar("ada", 4))
                .then(support.operations().save(new TTruck("ben", 12.5)))
                .block();

        List<TCar> cars = new ArrayList<>();
        StepVerifier.create(support.operations().findAll(TCar.class, QuerySpec.empty()))
                .recordWith(() -> cars)
                .expectNextCount(1)
                .verifyComplete();
        assertEquals(4, cars.get(0).getDoors());
    }

    @Test
    void countAndExistsOnAbstractRootAggregateAcrossConcreteTables() {
        // M1 회귀: TPC 추상 루트는 자기 테이블(t_vehicle)이 없으므로 count/exists가 구체 테이블들을 집계해야 한다.
        StepVerifier.create(support.operations().exists(TVehicle.class, QuerySpec.empty()))
                .expectNext(false)
                .verifyComplete();

        support.operations().save(new TCar("ada", 4))
                .then(support.operations().save(new TTruck("ben", 12.5)))
                .then(support.operations().save(new TCar("cyd", 2)))
                .block();

        StepVerifier.create(support.operations().count(TVehicle.class, QuerySpec.empty()))
                .expectNext(3L)
                .verifyComplete();
        StepVerifier.create(support.operations().exists(TVehicle.class, QuerySpec.empty()))
                .expectNext(true)
                .verifyComplete();
        StepVerifier.create(support.operations().count(TCar.class, QuerySpec.empty()))
                .expectNext(2L)
                .verifyComplete();
    }

    @Test
    void updatePersistsToConcreteTable() {
        TCar saved = (TCar) support.operations().save(new TCar("ada", 4)).block();
        saved.setName("ada-renamed");
        saved.setDoors(5);

        Mono<TVehicle> pipeline = support.operations().save(saved)
                .flatMap(updated -> support.operations().findById(TVehicle.class, saved.getId()));
        StepVerifier.create(pipeline)
                .assertNext(vehicle -> {
                    TCar found = assertInstanceOf(TCar.class, vehicle);
                    assertEquals("ada-renamed", found.getName());
                    assertEquals(5, found.getDoors());
                })
                .verifyComplete();
    }

    @Test
    void deleteRemovesConcreteRow() {
        TCar saved = (TCar) support.operations().save(new TCar("ada", 4)).block();

        StepVerifier.create(support.operations().delete(saved))
                .expectNextCount(1)
                .verifyComplete();

        StepVerifier.create(support.operations().findById(TVehicle.class, saved.getId()))
                .verifyComplete();
    }

    // --- TYPE() / TREAT() over the TABLE_PER_CLASS double-nested UNION ALL derived table ---

    @Test
    void jpqlEntitySelectWithTypeEqualsReturnsOnlyTablePerClassConcreteSubtype() {
        support.operations().save(new TCar("ada", 4))
                .then(support.operations().save(new TTruck("ben", 12.5)))
                .then(support.operations().save(new TCar("cyd", 2)))
                .block();

        List<TVehicle> result = new ArrayList<>();
        StepVerifier.create(
                        jpql.createQuery("SELECT e FROM TVehicle e WHERE TYPE(e) = TCar ORDER BY e.name", TVehicle.class)
                                .getResultList())
                .recordWith(() -> result)
                .expectNextCount(2)
                .verifyComplete();
        assertTrue(result.stream().allMatch(v -> v instanceof TCar), "TYPE(e) = TCar는 TCar row만 반환해야 한다");
        assertEquals(List.of("ada", "cyd"), result.stream().map(TVehicle::getName).toList());
    }

    @Test
    void jpqlScalarTreatProjectsTablePerClassSubtypeAttributeExcludingNonMatchingRows() {
        support.operations().save(new TCar("ada", 4))
                .then(support.operations().save(new TTruck("ben", 12.5)))
                .then(support.operations().save(new TCar("cyd", 2)))
                .block();

        StepVerifier.create(
                        jpql.createQuery("SELECT TREAT(e AS TCar).doors FROM TVehicle e ORDER BY e.name", Object.class)
                                .getResultList())
                .assertNext(v -> assertEquals(4, ((Number) v).intValue()))
                .assertNext(v -> assertEquals(2, ((Number) v).intValue()))
                .verifyComplete();
    }

    @Test
    void criteriaEntitySelectWithTypeEqualsReturnsOnlyTablePerClassConcreteSubtype() {
        support.operations().save(new TCar("ada", 4))
                .then(support.operations().save(new TTruck("ben", 12.5)))
                .block();

        CriteriaBuilder cb = criteria.getCriteriaBuilder();
        CriteriaQuery<TVehicle> cq = cb.createQuery(TVehicle.class);
        Root<TVehicle> e = cq.from(TVehicle.class);
        cq.select(e).where(cb.equal(e.type(), TCar.class)).orderBy(cb.asc(e.<String>get("name")));

        StepVerifier.create(criteria.createQuery(cq).getResultList())
                .assertNext(v -> assertEquals("ada", assertInstanceOf(TCar.class, v).getName()))
                .verifyComplete();
    }

    @Test
    void criteriaScalarTreatProjectsTablePerClassSubtypeAttribute() {
        support.operations().save(new TCar("ada", 4))
                .then(support.operations().save(new TTruck("ben", 12.5)))
                .block();

        CriteriaBuilder cb = criteria.getCriteriaBuilder();
        CriteriaQuery<Integer> cq = cb.createQuery(Integer.class);
        Root<TVehicle> e = cq.from(TVehicle.class);
        Root<TCar> car = cb.treat(e, TCar.class);
        cq.select(car.<Integer>get("doors")).orderBy(cb.asc(e.<String>get("name")));

        StepVerifier.create(criteria.createQuery(cq).getResultList())
                .assertNext(v -> assertEquals(4, ((Number) v).intValue()))
                .verifyComplete();
    }

    // --- fixtures ----------------------------------------------------------

    @Entity
    @Table(name = "t_vehicle")
    @Inheritance(strategy = InheritanceType.TABLE_PER_CLASS)
    @DiscriminatorColumn(name = "kind", discriminatorType = DiscriminatorType.STRING)
    @TableGenerator(name = "veh_gen", table = "nova_seq", pkColumnName = "seq_name",
            valueColumnName = "seq_val", pkColumnValue = "vehicle", initialValue = 1, allocationSize = 1)
    abstract static class TVehicle {
        @Id
        @GeneratedValue(strategy = GenerationType.TABLE, generator = "veh_gen")
        private Long id;
        private String name;

        TVehicle() {
        }

        TVehicle(String name) {
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
    @Table(name = "t_car")
    @DiscriminatorValue("CAR")
    static class TCar extends TVehicle {
        private int doors;

        TCar() {
        }

        TCar(String name, int doors) {
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
    @Table(name = "t_truck")
    @DiscriminatorValue("TRUCK")
    static class TTruck extends TVehicle {
        private double payload;

        TTruck() {
        }

        TTruck(String name, double payload) {
            super(name);
            this.payload = payload;
        }

        double getPayload() {
            return payload;
        }
    }
}
