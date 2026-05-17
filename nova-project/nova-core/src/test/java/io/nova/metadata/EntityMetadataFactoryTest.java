package io.nova.metadata;

import io.nova.support.fixtures.FixtureEntities.ConvertibleEntity;
import io.nova.support.fixtures.FixtureEntities.DefaultNamedEntity;
import io.nova.support.fixtures.FixtureEntities.DuplicateIdEntity;
import io.nova.support.fixtures.FixtureEntities.MissingEntityAnnotation;
import io.nova.support.fixtures.FixtureEntities.MissingIdEntity;
import io.nova.support.fixtures.FixtureEntities.SampleAccount;
import io.nova.support.fixtures.FixtureEntities.StaticFieldEntity;
import io.nova.support.fixtures.FixtureEntities.Status;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntityMetadataFactoryTest {
    private final EntityMetadataFactory factory = new EntityMetadataFactory(new DefaultNamingStrategy());

    @Test
    void createsMetadataFromAnnotations() {
        EntityMetadata<SampleAccount> metadata = factory.getEntityMetadata(SampleAccount.class);

        assertEquals("accounts", metadata.tableName());
        assertEquals("id", metadata.idProperty().propertyName());
        assertTrue(metadata.idProperty().generated());
        assertEquals("email_address", metadata.properties().get(1).columnName());
        assertFalse(metadata.properties().get(2).nullable());
    }

    @Test
    void usesDefaultNamingStrategyWhenAnnotationsDoNotOverride() {
        EntityMetadata<DefaultNamedEntity> metadata = factory.getEntityMetadata(DefaultNamedEntity.class);

        assertEquals("default_named_entity", metadata.tableName());
        assertEquals("entity_id", metadata.idProperty().columnName());
        assertEquals("display_name", metadata.properties().get(1).columnName());
    }

    @Test
    void ignoresStaticFieldsAndCachesMetadata() {
        EntityMetadata<StaticFieldEntity> first = factory.getEntityMetadata(StaticFieldEntity.class);
        EntityMetadata<StaticFieldEntity> second = factory.getEntityMetadata(StaticFieldEntity.class);

        assertEquals(2, first.properties().size());
        assertSame(first, second);
    }

    @Test
    void appliesRegisteredConverters() {
        factory.registerConverter(Status.class, new EnumStatusConverter());

        EntityMetadata<ConvertibleEntity> metadata = factory.getEntityMetadata(ConvertibleEntity.class);

        Object databaseValue = metadata.properties().get(1).toColumnValue(Status.ACTIVE);
        Object propertyValue = metadata.properties().get(1).toPropertyValue("inactive");

        assertEquals("active", databaseValue);
        assertEquals(Status.INACTIVE, propertyValue);
    }

    @Test
    void rejectsNonEntityTypes() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> factory.getEntityMetadata(MissingEntityAnnotation.class)
        );

        assertTrue(exception.getMessage().contains("is not annotated with @Entity"));
    }

    @Test
    void rejectsEntitiesWithoutId() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> factory.getEntityMetadata(MissingIdEntity.class)
        );

        assertTrue(exception.getMessage().contains("must declare a field annotated with @Id"));
    }

    @Test
    void rejectsEntitiesWithDuplicateIds() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> factory.getEntityMetadata(DuplicateIdEntity.class)
        );

        assertTrue(exception.getMessage().contains("declares multiple @Id properties"));
    }

    private static final class EnumStatusConverter implements io.nova.convert.AttributeConverter<Status, String> {
        @Override
        public String write(Status source) {
            return source.name().toLowerCase();
        }

        @Override
        public Status read(String source) {
            return Status.valueOf(source.toUpperCase());
        }
    }
}
