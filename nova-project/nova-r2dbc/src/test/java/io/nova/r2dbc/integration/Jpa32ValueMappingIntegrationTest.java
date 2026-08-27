package io.nova.r2dbc.integration;

import jakarta.persistence.Converter;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumeratedValue;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import io.nova.schema.SimpleSchemaInitializer;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class Jpa32ValueMappingIntegrationTest {

    @Test
    void autoApplyAndEnumeratedValueRoundTripAcrossScalarAndBasicCollections() {
        H2IntegrationTestSupport support = H2IntegrationTestSupport.create();
        support.metadataFactory().registerJpaConverter(CodeConverter.class);
        SimpleSchemaInitializer schema =
                new SimpleSchemaInitializer(support.operations(), support.metadataFactory(), support.dialect());

        ValueOwner owner = new ValueOwner();
        owner.code = new Code("scalar");
        owner.status = Status.CLOSED;
        owner.codes.add(new Code("set-value"));
        owner.byCode.put(new Code("map-key"), new Code("map-value"));

        StepVerifier.create(schema.create(ValueOwner.class)
                        .then(support.operations().save(owner))
                        .flatMap(saved -> support.operations().findById(ValueOwner.class, saved.id)))
                .assertNext(loaded -> {
                    assertEquals(new Code("scalar"), loaded.code);
                    assertEquals(Status.CLOSED, loaded.status);
                    assertEquals(Set.of(new Code("set-value")), loaded.codes);
                    assertEquals(Map.of(new Code("map-key"), new Code("map-value")), loaded.byCode);
                })
                .verifyComplete();
    }

    record Code(String value) {
    }

    @Converter(autoApply = true)
    public static class CodeConverter implements jakarta.persistence.AttributeConverter<Code, String> {
        @Override public String convertToDatabaseColumn(Code value) {
            return value == null ? null : value.value();
        }
        @Override public Code convertToEntityAttribute(String value) {
            return value == null ? null : new Code(value);
        }
    }

    enum Status {
        OPEN("O"), CLOSED("C");
        @EnumeratedValue final String databaseValue;
        Status(String databaseValue) { this.databaseValue = databaseValue; }
    }

    @Entity
    static class ValueOwner {
        @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
        Long id;
        Code code;
        Status status;
        @ElementCollection
        Set<Code> codes = new LinkedHashSet<>();
        @ElementCollection
        Map<Code, Code> byCode = new LinkedHashMap<>();
    }
}
