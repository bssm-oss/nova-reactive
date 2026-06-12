package io.nova.r2dbc.integration;

import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import io.nova.schema.SchemaInitializer;
import io.nova.schema.SimpleSchemaInitializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * JPA 표준 {@code @Convert} + {@link jakarta.persistence.AttributeConverter}가 H2 in-memory R2DBC driver와
 * end-to-end로 round-trip 되는지 검증한다. SQL string unit test만으로는 저장 타입(Y) 컬럼 DDL 생성과 driver
 * binding/decoding 호환을 보장할 수 없으므로(cycle 8 F4 회귀 메모리) 통합 테스트로 보호한다.
 */
class ConvertColumnRoundTripIntegrationTest {
    private H2IntegrationTestSupport support;

    @BeforeEach
    void setUp() {
        support = H2IntegrationTestSupport.create();
        SchemaInitializer schema =
                new SimpleSchemaInitializer(support.operations(), support.metadataFactory(), support.dialect());
        // 컬럼은 도메인 타입(Rgb)이 아니라 변환기의 저장 타입(Integer)으로 생성돼야 driver가 디코딩할 수 있다.
        schema.create(Swatch.class).block();
    }

    @Test
    void convertedValueRoundTripsThroughDatabase() {
        Mono<Swatch> pipeline = support.operations().save(new Swatch(new Rgb(255)))
                .flatMap(saved -> support.operations().findById(Swatch.class, saved.getId()));

        StepVerifier.create(pipeline)
                .assertNext(loaded -> {
                    assertNotNull(loaded.getColor(), "@Convert 컬럼이 도메인 타입으로 복원돼야 한다");
                    assertEquals(255, loaded.getColor().value());
                })
                .verifyComplete();
    }

    record Rgb(int value) {
    }

    public static class RgbConverter implements jakarta.persistence.AttributeConverter<Rgb, Integer> {
        @Override
        public Integer convertToDatabaseColumn(Rgb attribute) {
            return attribute == null ? null : attribute.value();
        }

        @Override
        public Rgb convertToEntityAttribute(Integer dbData) {
            return dbData == null ? null : new Rgb(dbData);
        }
    }

    @Entity
    @Table(name = "swatch")
    static class Swatch {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;

        @Convert(converter = RgbConverter.class)
        private Rgb color;

        Swatch() {
        }

        Swatch(Rgb color) {
            this.color = color;
        }

        Long getId() {
            return id;
        }

        Rgb getColor() {
            return color;
        }
    }
}
