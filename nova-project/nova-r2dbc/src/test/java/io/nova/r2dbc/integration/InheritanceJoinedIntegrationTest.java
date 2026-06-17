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
import io.nova.query.QuerySpec;
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

/**
 * {@code @Inheritance(JOINED)}가 H2 in-memory R2DBC driver와 end-to-end로 동작하는지 검증한다.
 * 멀티테이블 INSERT(root→subtype), JOIN 다형 SELECT, 멀티테이블 UPDATE/DELETE를 풀 라운드트립으로 본다.
 * SQL string unit test만으로는 driver 수용성(JOIN 디코딩/FK 순서/생성 키 회수)을 보장할 수 없으므로 필수다.
 */
class InheritanceJoinedIntegrationTest {
    private H2IntegrationTestSupport support;

    @BeforeEach
    void setUp() {
        support = H2IntegrationTestSupport.create();
        SchemaInitializer schema =
                new SimpleSchemaInitializer(support.operations(), support.metadataFactory(), support.dialect());
        schema.create(JVehicle.class, JCar.class, JTruck.class).block();
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
