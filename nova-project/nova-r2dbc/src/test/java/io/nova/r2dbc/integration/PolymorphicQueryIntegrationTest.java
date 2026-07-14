package io.nova.r2dbc.integration;

import io.nova.query.criteria.ReactiveCriteriaExecutor;
import io.nova.query.jpql.JpqlException;
import io.nova.query.jpql.JpqlExecutor;
import io.nova.schema.SchemaInitializer;
import io.nova.schema.SimpleSchemaInitializer;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JPQL/Criteria의 다형성 연산자 TYPE()/TREAT()가 SINGLE_TABLE 상속 계층에서 H2 in-memory R2DBC driver와
 * end-to-end로 동작하는지 검증한다 — 파싱/조립 → discriminator SQL 변환 → 리액티브 실행. SQL 문자열
 * 단위테스트만으로는 discriminator 바인딩/다형 hydration/row decoding 호환을 보장할 수 없으므로 왕복 통합
 * 검증한다.
 */
class PolymorphicQueryIntegrationTest {

    private H2IntegrationTestSupport support;
    private JpqlExecutor jpql;
    private ReactiveCriteriaExecutor criteria;

    @BeforeEach
    void setUp() {
        support = H2IntegrationTestSupport.create();
        SchemaInitializer schema =
                new SimpleSchemaInitializer(support.operations(), support.metadataFactory(), support.dialect());
        schema.create(Vehicle.class, Car.class, Truck.class).block();
        jpql = new JpqlExecutor(support.operations(), support.dialect(), support.metadataFactory(),
                Vehicle.class, Car.class, Truck.class);
        criteria = new ReactiveCriteriaExecutor(support.operations(), support.dialect(), support.metadataFactory());

        support.operations().save(new Car("ada", 4)).block();
        support.operations().save(new Truck("ben", 12.5)).block();
        support.operations().save(new Car("cyd", 2)).block();
    }

    // --- JPQL -----------------------------------------------------------------------------------

    @Test
    void jpqlEntitySelectWithTypeEqualsReturnsOnlyConcreteSubtype() {
        List<Vehicle> result = new ArrayList<>();
        StepVerifier.create(
                        jpql.createQuery("SELECT e FROM Vehicle e WHERE TYPE(e) = Car ORDER BY e.name", Vehicle.class)
                                .getResultList())
                .recordWith(() -> result)
                .expectNextCount(2)
                .verifyComplete();
        assertTrue(result.stream().allMatch(v -> v instanceof Car), "TYPE(e) = Car는 Car row만 반환해야 한다");
        assertEquals(List.of("ada", "cyd"), result.stream().map(Vehicle::getName).toList());
    }

    @Test
    void jpqlScalarTreatProjectsSubtypeAttributeFilteredByDiscriminator() {
        StepVerifier.create(
                        jpql.createQuery("SELECT TREAT(e AS Car).doors FROM Vehicle e ORDER BY e.name", Object.class)
                                .getResultList())
                .assertNext(v -> assertEquals(4, ((Number) v).intValue()))
                .assertNext(v -> assertEquals(2, ((Number) v).intValue()))
                .verifyComplete();
    }

    @Test
    void jpqlTypeInAggregateCountsMatchingSubtypes() {
        StepVerifier.create(
                        jpql.createQuery("SELECT COUNT(e) FROM Vehicle e WHERE TYPE(e) IN (Car)", Object.class)
                                .getSingleResult())
                .assertNext(v -> assertEquals(2L, ((Number) v).longValue()))
                .verifyComplete();
    }

    @Test
    void jpqlTreatInEntityReturningWhereFailsFast() {
        // 엔티티 반환 경로는 TYPE(e) = Subtype narrowing만 지원한다. TREAT 부등식 술어는 스칼라로 재작성해야 한다.
        StepVerifier.create(
                        jpql.createQuery("SELECT e FROM Vehicle e WHERE TREAT(e AS Car).doors > 3", Vehicle.class)
                                .getResultList())
                .expectError(JpqlException.class)
                .verify();
    }

    // --- Criteria -------------------------------------------------------------------------------

    @Test
    void criteriaEntitySelectWithTypeEqualsReturnsOnlyConcreteSubtype() {
        CriteriaBuilder cb = criteria.getCriteriaBuilder();
        CriteriaQuery<Vehicle> cq = cb.createQuery(Vehicle.class);
        Root<Vehicle> e = cq.from(Vehicle.class);
        cq.select(e).where(cb.equal(e.type(), Car.class)).orderBy(cb.asc(e.<String>get("name")));

        StepVerifier.create(criteria.createQuery(cq).getResultList())
                .assertNext(v -> assertEquals("ada", assertInstanceOf(Car.class, v).getName()))
                .assertNext(v -> assertEquals("cyd", assertInstanceOf(Car.class, v).getName()))
                .verifyComplete();
    }

    @Test
    void criteriaScalarTreatProjectsSubtypeAttribute() {
        CriteriaBuilder cb = criteria.getCriteriaBuilder();
        CriteriaQuery<Integer> cq = cb.createQuery(Integer.class);
        Root<Vehicle> e = cq.from(Vehicle.class);
        Root<Car> car = cb.treat(e, Car.class);
        cq.select(car.<Integer>get("doors")).orderBy(cb.asc(e.<String>get("name")));

        StepVerifier.create(criteria.createQuery(cq).getResultList())
                .assertNext(v -> assertEquals(4, ((Number) v).intValue()))
                .assertNext(v -> assertEquals(2, ((Number) v).intValue()))
                .verifyComplete();
    }

    // --- fixtures -------------------------------------------------------------------------------

    @Entity
    @Table(name = "poly_vehicle")
    @Inheritance(strategy = InheritanceType.SINGLE_TABLE)
    @DiscriminatorColumn(name = "kind", discriminatorType = DiscriminatorType.STRING)
    abstract static class Vehicle {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;
        private String name;

        Vehicle() {
        }

        Vehicle(String name) {
            this.name = name;
        }

        Long getId() {
            return id;
        }

        String getName() {
            return name;
        }
    }

    @Entity
    @DiscriminatorValue("CAR")
    static class Car extends Vehicle {
        private int doors;

        Car() {
        }

        Car(String name, int doors) {
            super(name);
            this.doors = doors;
        }

        int getDoors() {
            return doors;
        }
    }

    @Entity
    @DiscriminatorValue("TRUCK")
    static class Truck extends Vehicle {
        private double payload;

        Truck() {
        }

        Truck(String name, double payload) {
            super(name);
            this.payload = payload;
        }

        double getPayload() {
            return payload;
        }
    }
}
