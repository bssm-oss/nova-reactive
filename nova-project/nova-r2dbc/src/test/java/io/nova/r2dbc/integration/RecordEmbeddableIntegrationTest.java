package io.nova.r2dbc.integration;

import io.nova.schema.SchemaInitializer;
import io.nova.schema.SimpleSchemaInitializer;
import io.nova.annotation.Json;
import io.nova.json.JsonCodec;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Converter;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Embedded;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumeratedValue;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinColumns;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RecordEmbeddableIntegrationTest {
    private static SchemaInitializer schema(H2IntegrationTestSupport support) {
        SchemaInitializer schema =
                new SimpleSchemaInitializer(support.operations(), support.metadataFactory(), support.dialect());
        return schema;
    }

    @Test
    void embeddedRecordAndNestedRecordRoundTripWithAllNullSemantics() {
        H2IntegrationTestSupport support = H2IntegrationTestSupport.create();
        Person person = new Person("Ada", new Address("Seoul", new Coordinate(37, 127)));
        Person empty = new Person("Nobody", null);

        StepVerifier.create(schema(support).create(Person.class)
                        .then(support.operations().save(person))
                        .flatMap(saved -> support.operations().findById(Person.class, saved.id)))
                .assertNext(loaded -> assertEquals(
                        new Address("Seoul", new Coordinate(37, 127)), loaded.address))
                .verifyComplete();

        StepVerifier.create(support.operations().save(empty)
                        .flatMap(saved -> support.operations().findById(Person.class, saved.id)))
                .assertNext(loaded -> assertNull(loaded.address))
                .verifyComplete();
    }

    @Test
    void recordEmbeddedIdSupportsInsertUpdateFindAndDelete() {
        H2IntegrationTestSupport support = H2IntegrationTestSupport.create();
        OrderKey key = new OrderKey(11L, 2);
        OrderLine line = new OrderLine(key, 3);

        StepVerifier.create(schema(support).create(OrderLine.class)
                        .then(support.operations().save(line))
                        .then(support.operations().save(new OrderLine(key, 9)))
                        .then(support.operations().findById(OrderLine.class, new OrderKey(11L, 2))))
                .assertNext(loaded -> {
                    assertEquals(key, loaded.id);
                    assertEquals(9, loaded.quantity);
                })
                .verifyComplete();

        StepVerifier.create(support.operations().deleteById(OrderLine.class, key)
                        .then(support.operations().findById(OrderLine.class, key)))
                .verifyComplete();
    }

    @Test
    void relationTargetingRecordEmbeddedIdBuildsAnImmutableIdStub() {
        H2IntegrationTestSupport support = H2IntegrationTestSupport.create();
        OrderLine line = new OrderLine(new OrderKey(22L, 4), 1);
        Shipment shipment = new Shipment(line);

        StepVerifier.create(schema(support).create(OrderLine.class, Shipment.class)
                        .then(support.operations().save(line))
                        .then(support.operations().save(shipment))
                        .flatMap(saved -> support.operations().findById(Shipment.class, saved.id)))
                .assertNext(loaded -> assertEquals(new OrderKey(22L, 4), loaded.line.id))
                .verifyComplete();
    }

    @Test
    void recordCollectionElementsAndRecordMapEntriesRoundTrip() {
        H2IntegrationTestSupport support = H2IntegrationTestSupport.create();
        Catalog catalog = new Catalog();
        catalog.legs.add(new Leg("Seoul", "Busan"));
        catalog.prices.put(new Market("KR", 1), new Price("KRW", 1200));

        StepVerifier.create(schema(support).create(Catalog.class)
                        .then(support.operations().save(catalog))
                        .flatMap(saved -> support.operations().findById(Catalog.class, saved.id)))
                .assertNext(loaded -> {
                    assertEquals(List.of(new Leg("Seoul", "Busan")), loaded.legs);
                    assertEquals(Map.of(new Market("KR", 1), new Price("KRW", 1200)), loaded.prices);
                })
                .verifyComplete();
    }

    @Test
    void recordCollectionLeavesPreserveConvertersEnumsJsonAndDdlStorage() {
        H2IntegrationTestSupport support = H2IntegrationTestSupport.create(new PayloadCodec());
        support.metadataFactory().registerJpaConverter(CodeConverter.class);
        ConvertedCatalog catalog = new ConvertedCatalog();
        ConvertedValue value = new ConvertedValue(new Code("value"), State.CLOSED, new Payload("json"));
        catalog.values.add(value);
        ConvertedKey key = new ConvertedKey(new Code("key"), State.OPEN);
        catalog.byKey.put(key, value);

        StepVerifier.create(schema(support).create(ConvertedCatalog.class)
                        .then(support.operations().save(catalog))
                        .flatMap(saved -> support.operations().findById(ConvertedCatalog.class, saved.id)))
                .assertNext(loaded -> {
                    assertEquals(List.of(value), loaded.values);
                    assertEquals(Map.of(key, value), loaded.byKey);
                })
                .verifyComplete();
    }

    @Embeddable
    record Coordinate(Integer latitude, Integer longitude) {
    }

    @Embeddable
    record Address(String city, @Embedded Coordinate coordinate) {
    }

    @Entity
    @Table(name = "record_person")
    static class Person {
        @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
        Long id;
        String name;
        @Embedded Address address;

        Person() {
        }

        Person(String name, Address address) {
            this.name = name;
            this.address = address;
        }
    }

    @Embeddable
    record OrderKey(@Column(name = "order_id") Long orderId,
                    @Column(name = "line_no") Integer lineNo) {
    }

    @Entity
    @Table(name = "record_order_line")
    static class OrderLine {
        @EmbeddedId OrderKey id;
        int quantity;

        OrderLine() {
        }

        OrderLine(OrderKey id, int quantity) {
            this.id = id;
            this.quantity = quantity;
        }
    }

    @Entity
    @Table(name = "record_shipment")
    static class Shipment {
        @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
        Long id;

        @ManyToOne
        @JoinColumns({
                @JoinColumn(name = "order_fk", referencedColumnName = "order_id"),
                @JoinColumn(name = "line_fk", referencedColumnName = "line_no")
        })
        OrderLine line;

        Shipment() {
        }

        Shipment(OrderLine line) {
            this.line = line;
        }
    }

    @Embeddable
    record Leg(String origin, String destination) {
    }

    @Embeddable
    record Market(String region, Integer zone) {
    }

    @Embeddable
    record Price(String currency, Integer amount) {
    }

    @Entity
    @Table(name = "record_catalog")
    static class Catalog {
        @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
        Long id;

        @ElementCollection
        List<Leg> legs = new ArrayList<>();

        @ElementCollection
        @AttributeOverride(name = "key.region", column = @Column(name = "market_region"))
        @AttributeOverride(name = "key.zone", column = @Column(name = "market_zone"))
        Map<Market, Price> prices = new LinkedHashMap<>();
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

    enum State {
        OPEN("O"), CLOSED("C");
        @EnumeratedValue final String databaseValue;
        State(String databaseValue) {
            this.databaseValue = databaseValue;
        }
    }

    record Payload(String value) {
    }

    static final class PayloadCodec implements JsonCodec {
        @Override public String encode(Object value) {
            return ((Payload) value).value();
        }
        @Override public <T> T decode(String json, Class<T> type) {
            String value = json.length() >= 2 && json.startsWith("\"") && json.endsWith("\"")
                    ? json.substring(1, json.length() - 1) : json;
            return type.cast(new Payload(value));
        }
    }

    @Embeddable
    record ConvertedKey(Code keyCode, State keyState) {
    }

    @Embeddable
    record ConvertedValue(Code valueCode, State valueState, @Json Payload payload) {
    }

    @Entity
    @Table(name = "record_converted_catalog")
    static class ConvertedCatalog {
        @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
        Long id;
        @ElementCollection
        List<ConvertedValue> values = new ArrayList<>();
        @ElementCollection
        Map<ConvertedKey, ConvertedValue> byKey = new LinkedHashMap<>();
    }
}
