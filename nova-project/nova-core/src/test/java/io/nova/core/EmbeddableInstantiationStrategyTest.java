package io.nova.core;

import io.nova.metadata.DefaultNamingStrategy;
import io.nova.metadata.EntityMetadata;
import io.nova.metadata.EntityMetadataFactory;
import io.nova.metadata.PersistentProperty;
import jakarta.persistence.Access;
import jakarta.persistence.AccessType;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Transient;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmbeddableInstantiationStrategyTest {
    private final EntityMetadataFactory factory = new EntityMetadataFactory(new DefaultNamingStrategy());

    @Test
    void constructsNestedRecordsBottomUpAndKeepsAllNullInnerHostNull() {
        EntityMetadata<Office> metadata = factory.getEntityMetadata(Office.class);
        Office office = new Office();

        hydrate(office, metadata, "address.street", "Sejong-daero",
                "address.geo.country", "KR", "address.geo.city", "Seoul");

        assertEquals(new Address("Sejong-daero", new Geo("KR", "Seoul")), office.address);

        Office withoutGeo = new Office();
        hydrate(withoutGeo, metadata, "address.street", "Main",
                "address.geo.country", null, "address.geo.city", null);
        assertEquals(new Address("Main", null), withoutGeo.address);
    }

    @Test
    void leavesSingleValuedRecordNullWhenEveryColumnIsNull() {
        EntityMetadata<Office> metadata = factory.getEntityMetadata(Office.class);
        Office office = new Office();
        hydrate(office, metadata, "address.street", null,
                "address.geo.country", null, "address.geo.city", null);
        assertNull(office.address);
    }

    @Test
    void constructsRecordNestedInsideRegularEmbeddable() {
        EntityMetadata<MixedOffice> metadata = factory.getEntityMetadata(MixedOffice.class);
        MixedOffice office = new MixedOffice();
        hydrate(office, metadata, "address.label", "HQ",
                "address.geo.country", "KR", "address.geo.city", "Seoul");
        assertEquals("HQ", office.address.label);
        assertEquals(new Geo("KR", "Seoul"), office.address.geo);
    }

    @Test
    void reportsNullForPrimitiveRecordComponentWhenSiblingMakesHostPresent() {
        EntityMetadata<MeterEntity> metadata = factory.getEntityMetadata(MeterEntity.class);
        MeterEntity entity = new MeterEntity();
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> hydrate(entity, metadata, "meter.amount", null, "meter.unit", "kg"));
        assertTrue(error.getMessage().contains("primitive int"));
        assertTrue(error.getMessage().contains("amount"));
    }

    @Test
    void recordComponentsUseTheirAccessorsWithoutRequiringSetters() {
        PersistentProperty code = factory.getEntityMetadata(PropertyRecordEntity.class)
                .findProperty("value.code").orElseThrow();
        assertTrue(code.propertyAccess());
        assertEquals("code", code.propertyAccessGetter().getName());
        assertNull(code.propertyAccessSetter());
        assertEquals("x", code.read(new PropertyRecordEntity(new PropertyValue("x"))));
    }

    @Test
    void rejectsMapsIdMutationOfRecordEmbeddedIdAtMetadataBuild() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> factory.getEntityMetadata(DerivedRecordIdEntity.class));
        assertTrue(error.getMessage().contains("immutable record identifiers"));
    }

    @Test
    void rejectsUnmappedRecordComponentAtMetadataBuild() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> factory.getEntityMetadata(TransientRecordEntity.class));
        assertTrue(error.getMessage().contains("record component 'ignored' is not persistent"));
    }

    private static void hydrate(Object entity, EntityMetadata<?> metadata, Object... namesAndValues) {
        java.util.ArrayList<EmbeddableInstantiationStrategy.DecodedLeaf> leaves = new java.util.ArrayList<>();
        for (int i = 0; i < namesAndValues.length; i += 2) {
            PersistentProperty property = metadata.findProperty((String) namesAndValues[i]).orElseThrow();
            leaves.add(new EmbeddableInstantiationStrategy.DecodedLeaf(property, namesAndValues[i + 1]));
        }
        EmbeddableInstantiationStrategy.hydrateSingleValued(entity, leaves);
    }

    @Embeddable
    record Geo(String country, String city) {
    }

    @Embeddable
    record Address(String street, @Embedded Geo geo) {
    }

    @Entity
    static class Office {
        @Id Long id;
        @Embedded Address address;
    }

    @Embeddable
    static class MutableAddress {
        String label;
        @Embedded Geo geo;
        MutableAddress() {
        }
    }

    @Entity
    static class MixedOffice {
        @Id Long id;
        @Embedded MutableAddress address;
    }

    @Embeddable
    record Meter(int amount, String unit) {
    }

    @Entity
    static class MeterEntity {
        @Id Long id;
        @Embedded Meter meter;
    }

    @Embeddable
    @Access(AccessType.PROPERTY)
    record PropertyValue(String code) {
    }

    @Entity
    static class PropertyRecordEntity {
        @Id Long id;
        @Embedded PropertyValue value;

        PropertyRecordEntity() {
        }

        PropertyRecordEntity(PropertyValue value) {
            this.value = value;
        }
    }

    @Embeddable
    record DerivedId(Long parentId, String local) {
    }

    @Entity
    static class DerivedParent {
        @Id Long id;
    }

    @Entity
    static class DerivedRecordIdEntity {
        @jakarta.persistence.EmbeddedId DerivedId id;
        @ManyToOne @MapsId("parentId") DerivedParent parent;
    }

    @Embeddable
    record TransientValue(String stored, @Transient String ignored) {
    }

    @Entity
    static class TransientRecordEntity {
        @Id Long id;
        @Embedded TransientValue value;
    }
}
