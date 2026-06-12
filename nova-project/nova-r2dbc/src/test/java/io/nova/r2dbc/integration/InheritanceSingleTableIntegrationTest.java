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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SINGLE_TABLE 상속이 H2 in-memory R2DBC driver와 end-to-end로 동작하는지 검증한다.
 * SQL string unit test만으로는 row decoding/다형 인스턴스화/discriminator 바인딩 호환을 보장할 수 없으므로
 * (cycle 8 F4 회귀 메모리), 단일 테이블 DDL 생성 → save(discriminator 기록) → 다형 read를 모두 통합 검증한다.
 */
class InheritanceSingleTableIntegrationTest {
    private H2IntegrationTestSupport support;

    @BeforeEach
    void setUp() {
        support = H2IntegrationTestSupport.create();
        SchemaInitializer schema =
                new SimpleSchemaInitializer(support.operations(), support.metadataFactory(), support.dialect());
        // 단일 테이블 생성: 모든 서브타입 컬럼(doors/payload) + discriminator(kind)가 한 테이블에 union 된다.
        schema.create(IntVehicle.class, IntCar.class, IntTruck.class).block();
    }

    @Test
    void savePersistsDiscriminatorAndPolymorphicFindByIdReturnsConcreteType() {
        Mono<IntVehicle> carPipeline = support.operations().save(new IntCar("ada", 4))
                .flatMap(saved -> support.operations().findById(IntVehicle.class, saved.getId()));
        StepVerifier.create(carPipeline)
                .assertNext(vehicle -> {
                    IntCar found = assertInstanceOf(IntCar.class, vehicle, "kind='CAR' row는 IntCar로 매핑돼야 한다");
                    assertEquals("ada", found.getName());
                    assertEquals(4, found.getDoors());
                })
                .verifyComplete();

        Mono<IntVehicle> truckPipeline = support.operations().save(new IntTruck("ben", 12.5))
                .flatMap(saved -> support.operations().findById(IntVehicle.class, saved.getId()));
        StepVerifier.create(truckPipeline)
                .assertNext(vehicle -> {
                    IntTruck found = assertInstanceOf(IntTruck.class, vehicle, "kind='TRUCK' row는 IntTruck로 매핑돼야 한다");
                    assertEquals(12.5, found.getPayload());
                })
                .verifyComplete();
    }

    @Test
    void polymorphicFindAllReturnsMixedConcreteTypes() {
        support.operations().save(new IntCar("ada", 4))
                .then(support.operations().save(new IntTruck("ben", 12.5)))
                .then(support.operations().save(new IntCar("cyd", 2)))
                .block();

        List<IntVehicle> result = new ArrayList<>();
        StepVerifier.create(support.operations().findAll(IntVehicle.class, QuerySpec.empty()))
                .recordWith(() -> result)
                .expectNextCount(3)
                .verifyComplete();

        long cars = result.stream().filter(v -> v instanceof IntCar).count();
        long trucks = result.stream().filter(v -> v instanceof IntTruck).count();
        assertEquals(2, cars, "두 IntCar row가 IntCar로 매핑돼야 한다");
        assertEquals(1, trucks, "한 IntTruck row가 IntTruck로 매핑돼야 한다");
    }

    @Test
    void subtypeQueryIsRestrictedByDiscriminator() {
        IntCar savedCar = (IntCar) support.operations().save(new IntCar("ada", 4)).block();
        IntTruck savedTruck = (IntTruck) support.operations().save(new IntTruck("ben", 12.5)).block();

        // findById(IntCar, truckId): truck row는 kind='CAR' 제약에 걸려 비어 있어야 한다.
        StepVerifier.create(support.operations().findById(IntCar.class, savedTruck.getId()))
                .verifyComplete();
        // findById(IntCar, carId): car는 정상 조회된다.
        StepVerifier.create(support.operations().findById(IntCar.class, savedCar.getId()))
                .assertNext(car -> assertEquals(4, car.getDoors()))
                .verifyComplete();

        // findAll(IntCar)는 car만 반환한다.
        List<IntCar> cars = new ArrayList<>();
        StepVerifier.create(support.operations().findAll(IntCar.class, QuerySpec.empty()))
                .recordWith(() -> cars)
                .expectNextCount(1)
                .verifyComplete();
        assertTrue(cars.stream().allMatch(c -> c.getDoors() == 4));
    }

    @Entity
    @Table(name = "int_vehicle")
    @Inheritance(strategy = InheritanceType.SINGLE_TABLE)
    @DiscriminatorColumn(name = "kind", discriminatorType = DiscriminatorType.STRING)
    abstract static class IntVehicle {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;
        private String name;

        IntVehicle() {
        }

        IntVehicle(String name) {
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
    static class IntCar extends IntVehicle {
        private int doors;

        IntCar() {
        }

        IntCar(String name, int doors) {
            super(name);
            this.doors = doors;
        }

        int getDoors() {
            return doors;
        }
    }

    @Entity
    @DiscriminatorValue("TRUCK")
    static class IntTruck extends IntVehicle {
        private double payload;

        IntTruck() {
        }

        IntTruck(String name, double payload) {
            super(name);
            this.payload = payload;
        }

        double getPayload() {
            return payload;
        }
    }
}
