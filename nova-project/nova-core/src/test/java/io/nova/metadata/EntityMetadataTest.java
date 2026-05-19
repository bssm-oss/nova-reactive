package io.nova.metadata;

import io.nova.support.fixtures.FixtureEntities.SampleAccount;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntityMetadataTest {
    private final EntityMetadataFactory factory = new EntityMetadataFactory(new DefaultNamingStrategy());

    @Test
    void findPropertyReturnsExistingPropertyByName() {
        EntityMetadata<SampleAccount> metadata = factory.getEntityMetadata(SampleAccount.class);

        Optional<PersistentProperty> id = metadata.findProperty("id");
        Optional<PersistentProperty> email = metadata.findProperty("email");
        Optional<PersistentProperty> active = metadata.findProperty("active");

        assertTrue(id.isPresent());
        assertTrue(email.isPresent());
        assertTrue(active.isPresent());
        assertEquals("id", id.get().propertyName());
        assertEquals("email", email.get().propertyName());
        assertEquals("active", active.get().propertyName());
    }

    @Test
    void findPropertyReturnsSameInstanceAsPropertiesList() {
        EntityMetadata<SampleAccount> metadata = factory.getEntityMetadata(SampleAccount.class);

        PersistentProperty fromList = metadata.properties().stream()
                .filter(p -> p.propertyName().equals("email"))
                .findFirst()
                .orElseThrow();
        PersistentProperty fromIndex = metadata.findProperty("email").orElseThrow();

        assertSame(fromList, fromIndex);
    }

    @Test
    void findPropertyReturnsEmptyForUnknownName() {
        EntityMetadata<SampleAccount> metadata = factory.getEntityMetadata(SampleAccount.class);

        assertTrue(metadata.findProperty("nonexistent").isEmpty());
    }

    @Test
    void constructorRejectsDuplicatePropertyNames() {
        EntityMetadata<SampleAccount> metadata = factory.getEntityMetadata(SampleAccount.class);
        PersistentProperty idProperty = metadata.idProperty();
        PersistentProperty emailProperty = metadata.findProperty("email").orElseThrow();

        List<PersistentProperty> duplicates = List.of(idProperty, emailProperty, emailProperty);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new EntityMetadata<>(
                        SampleAccount.class,
                        "SampleAccount",
                        "accounts",
                        duplicates,
                        idProperty,
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of()
                )
        );
        assertTrue(exception.getMessage().contains("duplicate property name"));
        assertTrue(exception.getMessage().contains("email"));
    }
}
