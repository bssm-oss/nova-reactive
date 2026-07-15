package io.nova;

import jakarta.persistence.AssociationOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Table;
import io.nova.core.ReactiveEntityOperations;
import io.nova.query.NativeQuery;
import io.nova.schema.SchemaInitializer;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 서브클래스의 {@code @AssociationOverride}로 {@code @MappedSuperclass}에서 상속한 {@code @ManyToOne}
 * join 컬럼을 재지정한 뒤, 실제 r2dbc-h2 driver 위에서 (1) DDL이 재지정된 컬럼명으로 테이블을 만들고
 * (2) save/find가 그 컬럼으로 FK 값을 왕복하는지 검증한다.
 *
 * <p>SQL string 단위 테스트만으로는 override된 컬럼명이 DDL과 DML 양쪽에서 일관되게 쓰여 driver가
 * 수용하는지 알 수 없다 — 이 통합 테스트가 production {@link Nova} 배선으로 그 왕복을 고정한다.
 */
class AssociationOverrideH2IntegrationTest {

    private static final AtomicInteger DB_SEQ = new AtomicInteger();

    private ConnectionFactory freshConnectionFactory() {
        int seq = DB_SEQ.incrementAndGet();
        return ConnectionFactories.get(
                "r2dbc:h2:mem:///assocoverride" + seq + "?options=DB_CLOSE_DELAY=-1");
    }

    @Test
    void overriddenJoinColumnIsEmittedInDdlAndRoundTripsFk() {
        ConnectionFactory cf = freshConnectionFactory();
        SchemaInitializer schema = Nova.schemaInitializer(cf);
        ReactiveEntityOperations operations = Nova.create(cf);

        AtomicLong countryId = new AtomicLong();

        StepVerifier.create(
                schema.create(Country.class)
                        .then(schema.create(City.class))
                        .then(operations.save(new Country("KR")))
                        .flatMap(savedCountry -> {
                            assertNotNull(savedCountry.getId(), "country는 IDENTITY로 id가 채워져야 한다");
                            countryId.set(savedCountry.getId());
                            City city = new City("Seoul", 9700000, savedCountry);
                            return operations.save(city);
                        })
                        .flatMap(savedCity ->
                                // FK 값은 override된 컬럼 home_country_id에 실려야 한다. 이 컬럼이 DDL에 없거나
                                // DML이 다른 컬럼에 썼다면 이 SELECT가 컬럼 미존재/값 부재로 실패한다.
                                operations.queryNativeOne(
                                        NativeQuery.of(
                                                "select \"home_country_id\" as \"fk\" from \"assoc_city_it\""
                                                        + " where \"id\" = " + savedCity.getId()),
                                        row -> row.get("fk", Long.class)))
        ).assertNext(fk -> assertEquals(countryId.get(), fk,
                "FK 값은 @AssociationOverride로 재지정된 home_country_id 컬럼에 저장되어야 한다"))
                .verifyComplete();
    }

    @Test
    void findByIdRoundTripsScalarStateThroughOverriddenColumn() {
        ConnectionFactory cf = freshConnectionFactory();
        SchemaInitializer schema = Nova.schemaInitializer(cf);
        ReactiveEntityOperations operations = Nova.create(cf);

        StepVerifier.create(
                schema.create(Country.class)
                        .then(schema.create(City.class))
                        .then(operations.save(new Country("JP")))
                        .flatMap(savedCountry -> {
                            City city = new City("Tokyo", 13900000, savedCountry);
                            return operations.save(city);
                        })
                        .flatMap(savedCity -> operations.findById(City.class, savedCity.getId()))
        ).assertNext(loaded -> {
            assertNotNull(loaded.getId());
            assertEquals("Tokyo", loaded.getLabel());
            assertEquals(13900000, loaded.getPopulation());
        }).verifyComplete();
    }

    @Test
    void intermediateMappedSuperclassOverrideIsEmittedInDdlAndRoundTripsFk() {
        // country FK 컬럼 재지정을 concrete 엔티티가 아니라 중간 @MappedSuperclass(IntermediateBase)가 선언한다.
        // 계층 walk가 그 override를 적용해야 mid_country_id 컬럼이 DDL/DML에 일관되게 쓰인다.
        ConnectionFactory cf = freshConnectionFactory();
        SchemaInitializer schema = Nova.schemaInitializer(cf);
        ReactiveEntityOperations operations = Nova.create(cf);

        AtomicLong countryId = new AtomicLong();

        StepVerifier.create(
                schema.create(Country.class)
                        .then(schema.create(MidCity.class))
                        .then(operations.save(new Country("US")))
                        .flatMap(savedCountry -> {
                            countryId.set(savedCountry.getId());
                            return operations.save(new MidCity("Austin", 960000, savedCountry));
                        })
                        .flatMap(savedCity ->
                                operations.queryNativeOne(
                                        NativeQuery.of(
                                                "select \"mid_country_id\" as \"fk\" from \"assoc_mid_city_it\""
                                                        + " where \"id\" = " + savedCity.getId()),
                                        row -> row.get("fk", Long.class)))
        ).assertNext(fk -> assertEquals(countryId.get(), fk,
                "FK 값은 중간 @MappedSuperclass의 @AssociationOverride로 재지정된 mid_country_id 컬럼에 저장되어야 한다"))
                .verifyComplete();
    }

    // --- fixtures -----------------------------------------------------------

    @Entity
    @Table(name = "assoc_country_it")
    public static class Country {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;

        @Column(name = "name")
        private String name;

        public Country() {
        }

        public Country(String name) {
            this.name = name;
        }

        public Long getId() {
            return id;
        }
    }

    @MappedSuperclass
    public static abstract class RegionBase {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;

        @Column(name = "label")
        private String label;

        @ManyToOne
        @JoinColumn(name = "region_country_id")
        private Country country;

        public Long getId() {
            return id;
        }

        public String getLabel() {
            return label;
        }

        public Country getCountry() {
            return country;
        }

        void init(String label, Country country) {
            this.label = label;
            this.country = country;
        }
    }

    @Entity
    @Table(name = "assoc_city_it")
    @AssociationOverride(name = "country", joinColumns = @JoinColumn(name = "home_country_id"))
    public static class City extends RegionBase {
        @Column(name = "population")
        private Integer population;

        public City() {
        }

        public City(String label, Integer population, Country country) {
            init(label, country);
            this.population = population;
        }

        public Integer getPopulation() {
            return population;
        }
    }

    /**
     * 중간 {@code @MappedSuperclass}가 상위 {@code @MappedSuperclass}({@link RegionBase})에서 상속한
     * {@code country} 관계의 join 컬럼을 재지정한다. concrete 엔티티는 override를 선언하지 않는다.
     */
    @MappedSuperclass
    @AssociationOverride(name = "country", joinColumns = @JoinColumn(name = "mid_country_id"))
    public static abstract class IntermediateBase extends RegionBase {
        @Column(name = "district")
        private String district;

        public String getDistrict() {
            return district;
        }
    }

    @Entity
    @Table(name = "assoc_mid_city_it")
    public static class MidCity extends IntermediateBase {
        @Column(name = "population")
        private Integer population;

        public MidCity() {
        }

        public MidCity(String label, Integer population, Country country) {
            init(label, country);
            this.population = population;
        }

        public Integer getPopulation() {
            return population;
        }
    }
}
