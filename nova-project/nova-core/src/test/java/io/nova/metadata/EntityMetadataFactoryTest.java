package io.nova.metadata;

import io.nova.support.fixtures.FixtureEntities.AuditedAccount;
import io.nova.support.fixtures.FixtureEntities.ConvertibleEntity;
import io.nova.support.fixtures.FixtureEntities.DefaultNamedEntity;
import io.nova.support.fixtures.FixtureEntities.DuplicateCreatedAtEntity;
import io.nova.support.fixtures.FixtureEntities.DuplicateIdEntity;
import io.nova.support.fixtures.FixtureEntities.DuplicateSoftDeleteEntity;
import io.nova.support.fixtures.FixtureEntities.DuplicateVersionEntity;
import io.nova.support.fixtures.FixtureEntities.IdVersionConflictEntity;
import io.nova.support.fixtures.FixtureEntities.IntegerVersionedAccount;
import io.nova.support.fixtures.FixtureEntities.MissingEntityAnnotation;
import io.nova.support.fixtures.FixtureEntities.MissingIdEntity;
import io.nova.support.fixtures.FixtureEntities.SampleAccount;
import io.nova.support.fixtures.FixtureEntities.ShortVersionedAccount;
import io.nova.support.fixtures.FixtureEntities.SoftDeletableAccount;
import io.nova.support.fixtures.FixtureEntities.SoftDeletableLocalAccount;
import io.nova.support.fixtures.FixtureEntities.SoftDeletableOffsetAccount;
import io.nova.support.fixtures.FixtureEntities.SoftDeleteOnIdEntity;
import io.nova.support.fixtures.FixtureEntities.StaticFieldEntity;
import io.nova.support.fixtures.FixtureEntities.Status;
import io.nova.support.fixtures.FixtureEntities.UnsupportedAuditTypeEntity;
import io.nova.support.fixtures.FixtureEntities.UnsupportedSoftDeleteTypeEntity;
import io.nova.support.fixtures.FixtureEntities.UnsupportedVersionTypeEntity;
import io.nova.support.fixtures.FixtureEntities.VersionedAccount;
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

    @Test
    void identifiesCreatedAtAndUpdatedAtProperties() {
        EntityMetadata<AuditedAccount> metadata = factory.getEntityMetadata(AuditedAccount.class);

        assertTrue(metadata.createdAtProperty().isPresent());
        assertEquals("createdAt", metadata.createdAtProperty().get().propertyName());
        assertTrue(metadata.createdAtProperty().get().createdAt());

        assertTrue(metadata.updatedAtProperty().isPresent());
        assertEquals("updatedAt", metadata.updatedAtProperty().get().propertyName());
        assertTrue(metadata.updatedAtProperty().get().updatedAt());
    }

    @Test
    void entitiesWithoutAuditAnnotationsReportEmptyAuditProperties() {
        EntityMetadata<SampleAccount> metadata = factory.getEntityMetadata(SampleAccount.class);

        assertTrue(metadata.createdAtProperty().isEmpty());
        assertTrue(metadata.updatedAtProperty().isEmpty());
    }

    @Test
    void rejectsUnsupportedAuditFieldType() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> factory.getEntityMetadata(UnsupportedAuditTypeEntity.class)
        );

        assertTrue(exception.getMessage().contains("Unsupported audit type java.lang.Long"));
        assertTrue(exception.getMessage().contains("createdAtEpoch"));
    }

    @Test
    void rejectsEntitiesWithDuplicateCreatedAt() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> factory.getEntityMetadata(DuplicateCreatedAtEntity.class)
        );

        assertTrue(exception.getMessage().contains("declares multiple @CreatedAt properties"));
    }

    @Test
    void recognizesSoftDeleteProperty() {
        EntityMetadata<SoftDeletableAccount> metadata = factory.getEntityMetadata(SoftDeletableAccount.class);

        assertTrue(metadata.softDeleteProperty().isPresent());
        assertEquals("deletedAt", metadata.softDeleteProperty().get().propertyName());
        assertEquals("deleted_at", metadata.softDeleteProperty().get().columnName());
        assertTrue(metadata.softDeleteProperty().get().softDelete());
    }

    @Test
    void absentSoftDeletePropertyWhenNotAnnotated() {
        EntityMetadata<SampleAccount> metadata = factory.getEntityMetadata(SampleAccount.class);

        assertFalse(metadata.softDeleteProperty().isPresent());
    }

    @Test
    void recognizesSoftDeleteOnLocalDateTime() {
        EntityMetadata<SoftDeletableLocalAccount> metadata = factory.getEntityMetadata(SoftDeletableLocalAccount.class);

        assertTrue(metadata.softDeleteProperty().isPresent());
        assertEquals(java.time.LocalDateTime.class, metadata.softDeleteProperty().get().javaType());
    }

    @Test
    void recognizesSoftDeleteOnOffsetDateTime() {
        EntityMetadata<SoftDeletableOffsetAccount> metadata = factory.getEntityMetadata(SoftDeletableOffsetAccount.class);

        assertTrue(metadata.softDeleteProperty().isPresent());
        assertEquals(java.time.OffsetDateTime.class, metadata.softDeleteProperty().get().javaType());
    }

    @Test
    void rejectsEntitiesWithDuplicateSoftDelete() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> factory.getEntityMetadata(DuplicateSoftDeleteEntity.class)
        );

        assertTrue(exception.getMessage().contains("declares multiple @SoftDelete properties"));
    }

    @Test
    void rejectsUnsupportedSoftDeleteType() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> factory.getEntityMetadata(UnsupportedSoftDeleteTypeEntity.class)
        );

        assertTrue(exception.getMessage().contains("unsupported @SoftDelete type"));
    }

    @Test
    void rejectsSoftDeleteOnIdField() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> factory.getEntityMetadata(SoftDeleteOnIdEntity.class)
        );

        assertTrue(exception.getMessage().contains("cannot be annotated with both @Id and @SoftDelete"));
    }

    @Test
    void recognizesLongVersionProperty() {
        EntityMetadata<VersionedAccount> metadata = factory.getEntityMetadata(VersionedAccount.class);

        assertTrue(metadata.versionProperty().isPresent());
        assertEquals("version", metadata.versionProperty().get().propertyName());
        assertEquals(Long.class, metadata.versionProperty().get().javaType());
        assertTrue(metadata.versionProperty().get().version());
        assertFalse(metadata.versionProperty().get().id());
    }

    @Test
    void recognizesIntegerAndShortVersionProperties() {
        EntityMetadata<IntegerVersionedAccount> intMetadata =
                factory.getEntityMetadata(IntegerVersionedAccount.class);
        EntityMetadata<ShortVersionedAccount> shortMetadata =
                factory.getEntityMetadata(ShortVersionedAccount.class);

        assertTrue(intMetadata.versionProperty().isPresent());
        assertEquals(Integer.class, intMetadata.versionProperty().get().javaType());
        assertTrue(shortMetadata.versionProperty().isPresent());
        assertEquals(Short.class, shortMetadata.versionProperty().get().javaType());
    }

    @Test
    void absentVersionPropertyForEntitiesWithoutAnnotation() {
        EntityMetadata<SampleAccount> metadata = factory.getEntityMetadata(SampleAccount.class);

        assertFalse(metadata.versionProperty().isPresent());
    }

    @Test
    void rejectsEntitiesWithDuplicateVersionProperties() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> factory.getEntityMetadata(DuplicateVersionEntity.class)
        );

        assertTrue(exception.getMessage().contains("declares multiple @Version properties"));
    }

    @Test
    void rejectsUnsupportedVersionType() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> factory.getEntityMetadata(UnsupportedVersionTypeEntity.class)
        );

        assertTrue(exception.getMessage().contains("Unsupported version type"));
        assertTrue(exception.getMessage().contains("java.lang.String"));
    }

    @Test
    void rejectsFieldsAnnotatedWithBothIdAndVersion() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> factory.getEntityMetadata(IdVersionConflictEntity.class)
        );

        assertTrue(exception.getMessage().contains("cannot be both @Id and @Version"));
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
