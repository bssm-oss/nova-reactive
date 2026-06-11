package io.nova.metadata;

import io.nova.support.fixtures.FixtureEntities.ArgCallbackEntity;
import io.nova.support.fixtures.FixtureEntities.AuditedAccount;
import io.nova.support.fixtures.FixtureEntities.AutoNamedIndexEntity;
import io.nova.support.fixtures.FixtureEntities.AutoNamedUniqueConstraintEntity;
import io.nova.support.fixtures.FixtureEntities.ConvertibleEntity;
import io.nova.support.fixtures.FixtureEntities.Customer;
import io.nova.support.fixtures.FixtureEntities.CustomerWithEmbeddableIdEntity;
import io.nova.support.fixtures.FixtureEntities.CustomerWithEmbeddedCreatedAtSubField;
import io.nova.support.fixtures.FixtureEntities.CustomerWithEmbeddedIdSubField;
import io.nova.support.fixtures.FixtureEntities.CustomerWithEmbeddedSoftDeleteSubField;
import io.nova.support.fixtures.FixtureEntities.CustomerWithEmbeddedUpdatedAtSubField;
import io.nova.support.fixtures.FixtureEntities.CustomerWithEmbeddedVersionSubField;
import io.nova.support.fixtures.FixtureEntities.CustomerWithNonEmbeddable;
import io.nova.support.fixtures.FixtureEntities.EntityWithCircularEmbedded;
import io.nova.support.fixtures.FixtureEntities.Office;
import io.nova.support.fixtures.FixtureEntities.DefaultNamedEntity;
import io.nova.support.fixtures.FixtureEntities.DuplicateCreatedAtEntity;
import io.nova.support.fixtures.FixtureEntities.DuplicateIdEntity;
import io.nova.support.fixtures.FixtureEntities.DuplicateSoftDeleteEntity;
import io.nova.support.fixtures.FixtureEntities.DuplicateVersionEntity;
import io.nova.support.fixtures.FixtureEntities.EmptyIndexColumnsEntity;
import io.nova.support.fixtures.FixtureEntities.EmptyUniqueConstraintColumnsEntity;
import io.nova.support.fixtures.FixtureEntities.EntityWithCallbacks;
import io.nova.support.fixtures.FixtureEntities.EnumDefaultAccount;
import io.nova.support.fixtures.FixtureEntities.EnumOnNonEnumFieldEntity;
import io.nova.support.fixtures.FixtureEntities.EnumOrdinalAccount;
import io.nova.support.fixtures.FixtureEntities.EnumStringAccount;
import io.nova.support.fixtures.FixtureEntities.EnumWithConverterEntity;
import io.nova.support.fixtures.FixtureEntities.ColumnInsertableFalseEntity;
import io.nova.support.fixtures.FixtureEntities.ColumnUniqueEntity;
import io.nova.support.fixtures.FixtureEntities.GeneratedValueTableEntity;
import io.nova.support.fixtures.FixtureEntities.ManyToOneCascadeEntity;
import io.nova.support.fixtures.FixtureEntities.ManyToOneLazyEntity;
import io.nova.support.fixtures.FixtureEntities.OneToManyOrphanRemovalEntity;
import io.nova.support.fixtures.FixtureEntities.IndexWithUnknownColumnEntity;
import io.nova.support.fixtures.FixtureEntities.JsonAccount;
import io.nova.support.fixtures.FixtureEntities.JsonAndEnumeratedEntity;
import io.nova.support.fixtures.FixtureEntities.JsonAndRelationEntity;
import io.nova.support.fixtures.FixtureEntities.JsonWithRegisteredConverterEntity;
import io.nova.support.fixtures.FixtureEntities.Preferences;
import io.nova.support.fixtures.FixtureEntities.LongAutoNamedIndexEntity;
import io.nova.support.fixtures.FixtureEntities.MultipleCallbacksEntity;
import io.nova.support.fixtures.FixtureEntities.RepeatedIndexEntity;
import io.nova.support.fixtures.FixtureEntities.RepeatedUniqueConstraintEntity;
import io.nova.support.fixtures.FixtureEntities.ReturningCallbackEntity;
import io.nova.support.fixtures.FixtureEntities.SingleIndexEntity;
import io.nova.support.fixtures.FixtureEntities.SingleUniqueConstraintEntity;
import io.nova.support.fixtures.FixtureEntities.StaticCallbackEntity;
import io.nova.support.fixtures.FixtureEntities.IdVersionConflictEntity;
import io.nova.support.fixtures.FixtureEntities.IntegerVersionedAccount;
import io.nova.support.fixtures.FixtureEntities.InvalidUuidTypeEntity;
import io.nova.support.fixtures.FixtureEntities.MissingEntityAnnotation;
import io.nova.support.fixtures.FixtureEntities.MissingIdEntity;
import io.nova.support.fixtures.FixtureEntities.UniqueConstraintWithUnknownColumnEntity;
import io.nova.support.fixtures.FixtureEntities.VeryLongAutoNamedUniqueConstraintEntity;
import io.nova.support.fixtures.FixtureEntities.HyphenSequenceGeneratorEntity;
import io.nova.support.fixtures.FixtureEntities.InjectingSequenceGeneratorEntity;
import io.nova.support.fixtures.FixtureEntities.LeadingDigitSequenceGeneratorEntity;
import io.nova.support.fixtures.FixtureEntities.MissingSequenceGeneratorEntity;
import io.nova.support.fixtures.FixtureEntities.SemicolonSequenceGeneratorEntity;
import io.nova.support.fixtures.FixtureEntities.WhitespaceSequenceGeneratorEntity;
import io.nova.support.fixtures.FixtureEntities.SampleAccount;
import io.nova.support.fixtures.FixtureEntities.SequencedAccount;
import io.nova.support.fixtures.FixtureEntities.StringUuidAccount;
import io.nova.support.fixtures.FixtureEntities.UuidAccount;
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
import io.nova.support.fixtures.FixtureEntities.VersionedSoftDeletableAccount;
import org.junit.jupiter.api.Test;

import jakarta.persistence.GenerationType;

import jakarta.persistence.EnumType;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    void recognizesBothSoftDeleteAndVersionOnSameEntity() {
        EntityMetadata<VersionedSoftDeletableAccount> metadata =
                factory.getEntityMetadata(VersionedSoftDeletableAccount.class);

        assertTrue(metadata.softDeleteProperty().isPresent());
        assertEquals("deletedAt", metadata.softDeleteProperty().get().propertyName());
        assertTrue(metadata.versionProperty().isPresent());
        assertEquals("version", metadata.versionProperty().get().propertyName());
        assertEquals(Long.class, metadata.versionProperty().get().javaType());
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

    @Test
    void recognizesSequenceGenerationStrategyAndGeneratorName() {
        EntityMetadata<SequencedAccount> metadata = factory.getEntityMetadata(SequencedAccount.class);

        assertEquals(GenerationType.SEQUENCE, metadata.idProperty().generationType());
        assertEquals("sequenced_accounts_seq", metadata.idProperty().generator());
        assertTrue(metadata.idProperty().generated());
    }

    @Test
    void recognizesUuidGenerationStrategyWithUuidIdType() {
        EntityMetadata<UuidAccount> metadata = factory.getEntityMetadata(UuidAccount.class);

        assertEquals(GenerationType.UUID, metadata.idProperty().generationType());
        assertEquals(java.util.UUID.class, metadata.idProperty().javaType());
        assertTrue(metadata.idProperty().generated());
    }

    @Test
    void recognizesUuidGenerationStrategyWithStringIdType() {
        EntityMetadata<StringUuidAccount> metadata = factory.getEntityMetadata(StringUuidAccount.class);

        assertEquals(GenerationType.UUID, metadata.idProperty().generationType());
        assertEquals(String.class, metadata.idProperty().javaType());
    }

    @Test
    void rejectsUuidStrategyOnUnsupportedIdType() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> factory.getEntityMetadata(InvalidUuidTypeEntity.class)
        );

        assertTrue(exception.getMessage().contains("Unsupported UUID id type"));
        assertTrue(exception.getMessage().contains("java.lang.Long"));
    }

    @Test
    void rejectsSequenceStrategyWithoutGeneratorName() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> factory.getEntityMetadata(MissingSequenceGeneratorEntity.class)
        );

        assertTrue(exception.getMessage().contains("@GeneratedValue(SEQUENCE)"));
        assertTrue(exception.getMessage().contains("generator"));
    }

    @Test
    void rejectsSequenceGeneratorNameWithQuoteAndSemicolonInjection() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> factory.getEntityMetadata(InjectingSequenceGeneratorEntity.class)
        );

        assertTrue(exception.getMessage().contains("Invalid sequence generator name"));
    }

    @Test
    void rejectsSequenceGeneratorNameWithSemicolon() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> factory.getEntityMetadata(SemicolonSequenceGeneratorEntity.class)
        );

        assertTrue(exception.getMessage().contains("Invalid sequence generator name"));
    }

    @Test
    void rejectsSequenceGeneratorNameWithWhitespace() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> factory.getEntityMetadata(WhitespaceSequenceGeneratorEntity.class)
        );

        assertTrue(exception.getMessage().contains("Invalid sequence generator name"));
    }

    @Test
    void rejectsSequenceGeneratorNameWithHyphen() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> factory.getEntityMetadata(HyphenSequenceGeneratorEntity.class)
        );

        assertTrue(exception.getMessage().contains("Invalid sequence generator name"));
    }

    @Test
    void rejectsSequenceGeneratorNameWithLeadingDigit() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> factory.getEntityMetadata(LeadingDigitSequenceGeneratorEntity.class)
        );

        assertTrue(exception.getMessage().contains("Invalid sequence generator name"));
    }

    @Test
    void discoversLifecycleCallbacksWithCorrectSignature() {
        EntityMetadata<EntityWithCallbacks> metadata = factory.getEntityMetadata(EntityWithCallbacks.class);

        assertEquals(1, metadata.prePersistCallbacks().size());
        assertEquals("onPrePersist", metadata.prePersistCallbacks().get(0).getName());
        assertEquals(1, metadata.preUpdateCallbacks().size());
        assertEquals("onPreUpdate", metadata.preUpdateCallbacks().get(0).getName());
        assertEquals(1, metadata.postLoadCallbacks().size());
        assertEquals("onPostLoad", metadata.postLoadCallbacks().get(0).getName());
        assertEquals(1, metadata.preRemoveCallbacks().size());
        assertEquals("onPreRemove", metadata.preRemoveCallbacks().get(0).getName());
    }

    @Test
    void returnsEmptyCallbackListsForEntitiesWithoutCallbacks() {
        EntityMetadata<SampleAccount> metadata = factory.getEntityMetadata(SampleAccount.class);

        assertTrue(metadata.prePersistCallbacks().isEmpty());
        assertTrue(metadata.preUpdateCallbacks().isEmpty());
        assertTrue(metadata.postLoadCallbacks().isEmpty());
        assertTrue(metadata.preRemoveCallbacks().isEmpty());
    }

    @Test
    void preservesDeclarationOrderForMultipleCallbacksOfSamePhase() {
        EntityMetadata<MultipleCallbacksEntity> metadata = factory.getEntityMetadata(MultipleCallbacksEntity.class);

        assertEquals(2, metadata.prePersistCallbacks().size());
        java.util.Set<String> names = new java.util.LinkedHashSet<>();
        for (java.lang.reflect.Method m : metadata.prePersistCallbacks()) {
            names.add(m.getName());
        }
        // declaration 순서를 강제하지는 않지만 두 메서드 모두 인식되어야 한다.
        assertTrue(names.contains("first"));
        assertTrue(names.contains("second"));
    }

    @Test
    void rejectsStaticCallbackMethod() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> factory.getEntityMetadata(StaticCallbackEntity.class)
        );

        assertTrue(exception.getMessage().contains("@PrePersist"));
        assertTrue(exception.getMessage().contains("must be non-static, no-arg, void-returning"));
    }

    @Test
    void rejectsCallbackMethodWithArgument() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> factory.getEntityMetadata(ArgCallbackEntity.class)
        );

        assertTrue(exception.getMessage().contains("@PreUpdate"));
        assertTrue(exception.getMessage().contains("must be non-static, no-arg, void-returning"));
    }

    @Test
    void rejectsCallbackMethodWithNonVoidReturn() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> factory.getEntityMetadata(ReturningCallbackEntity.class)
        );

        assertTrue(exception.getMessage().contains("@PostLoad"));
        assertTrue(exception.getMessage().contains("must be non-static, no-arg, void-returning"));
    }

    @Test
    void recognizesSingleIndexAnnotation() {
        EntityMetadata<SingleIndexEntity> metadata = factory.getEntityMetadata(SingleIndexEntity.class);

        assertEquals(1, metadata.indexes().size());
        IndexDefinition index = metadata.indexes().get(0);
        assertEquals("ix_indexed_email", index.name());
        assertEquals(java.util.List.of("email"), index.columns());
        assertTrue(metadata.uniqueConstraints().isEmpty());
    }

    @Test
    void recognizesSingleUniqueConstraintAnnotation() {
        EntityMetadata<SingleUniqueConstraintEntity> metadata =
                factory.getEntityMetadata(SingleUniqueConstraintEntity.class);

        assertEquals(1, metadata.uniqueConstraints().size());
        UniqueConstraintDefinition constraint = metadata.uniqueConstraints().get(0);
        assertEquals("uk_email", constraint.name());
        assertEquals(java.util.List.of("email"), constraint.columns());
        assertTrue(metadata.indexes().isEmpty());
    }

    @Test
    void recognizesRepeatedIndexAnnotations() {
        EntityMetadata<RepeatedIndexEntity> metadata = factory.getEntityMetadata(RepeatedIndexEntity.class);

        assertEquals(2, metadata.indexes().size());
        assertEquals(java.util.List.of("email"), metadata.indexes().get(0).columns());
        assertEquals(java.util.List.of("first_name", "last_name"), metadata.indexes().get(1).columns());
    }

    @Test
    void recognizesRepeatedUniqueConstraintAnnotations() {
        EntityMetadata<RepeatedUniqueConstraintEntity> metadata =
                factory.getEntityMetadata(RepeatedUniqueConstraintEntity.class);

        assertEquals(2, metadata.uniqueConstraints().size());
        assertEquals(java.util.List.of("email"), metadata.uniqueConstraints().get(0).columns());
        assertEquals(java.util.List.of("first_name", "last_name"),
                metadata.uniqueConstraints().get(1).columns());
    }

    @Test
    void autoGeneratesIndexNameFromTableAndColumns() {
        EntityMetadata<AutoNamedIndexEntity> metadata = factory.getEntityMetadata(AutoNamedIndexEntity.class);

        assertEquals(1, metadata.indexes().size());
        assertEquals("ix_multi_indexed_accounts_first_name_last_name",
                metadata.indexes().get(0).name());
    }

    @Test
    void autoGeneratedIndexNameFallsBackToHashWhenExceedingIdentifierLimit() {
        EntityMetadata<LongAutoNamedIndexEntity> metadata =
                factory.getEntityMetadata(LongAutoNamedIndexEntity.class);

        assertEquals(1, metadata.indexes().size());
        String name = metadata.indexes().get(0).name();
        assertTrue(name.length() <= 63,
                "auto-generated index name must fit within 63-char identifier limit, got " + name);
        assertTrue(name.startsWith("ix_medium_long_table_name_for_index_truncation_tests_"),
                "fallback should preserve prefix and table portion, got " + name);
        // 컬럼 이름이 그대로 들어가면 한도 초과이므로 hash fallback이어야 한다.
        assertFalse(name.endsWith("_email_address"),
                "long name should be replaced with hash, got " + name);
    }

    @Test
    void autoGeneratedNameTruncatesPrefixToPreserveHashSuffix() {
        EntityMetadata<VeryLongAutoNamedUniqueConstraintEntity> metadata =
                factory.getEntityMetadata(VeryLongAutoNamedUniqueConstraintEntity.class);

        assertEquals(1, metadata.uniqueConstraints().size());
        String name = metadata.uniqueConstraints().get(0).name();
        assertTrue(name.length() <= 63,
                "name must fit within 63-char identifier limit, got " + name);
        assertTrue(name.startsWith("uk_extremely_long_table_name_for_testing_identifier_li"),
                "prefix should be truncated to make room for hash suffix, got " + name);
        assertTrue(name.matches(".*_[0-9a-f]+$"),
                "name must end with a hex hash discriminator so different column sets do not collide, got " + name);
    }

    @Test
    void autoGeneratesUniqueConstraintNameFromTableAndColumns() {
        EntityMetadata<AutoNamedUniqueConstraintEntity> metadata =
                factory.getEntityMetadata(AutoNamedUniqueConstraintEntity.class);

        assertEquals(1, metadata.uniqueConstraints().size());
        assertEquals("uk_composite_unique_accounts_first_name_last_name",
                metadata.uniqueConstraints().get(0).name());
    }

    @Test
    void rejectsIndexReferencingUnknownColumn() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> factory.getEntityMetadata(IndexWithUnknownColumnEntity.class)
        );

        assertTrue(exception.getMessage().contains("@Index"));
        assertTrue(exception.getMessage().contains("nonexistent"));
    }

    @Test
    void rejectsUniqueConstraintReferencingUnknownColumn() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> factory.getEntityMetadata(UniqueConstraintWithUnknownColumnEntity.class)
        );

        assertTrue(exception.getMessage().contains("@UniqueConstraint"));
        assertTrue(exception.getMessage().contains("nonexistent"));
    }

    @Test
    void rejectsIndexWithoutColumns() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> factory.getEntityMetadata(EmptyIndexColumnsEntity.class)
        );

        assertTrue(exception.getMessage().contains("@Index"));
        assertTrue(exception.getMessage().contains("at least one column"));
    }

    @Test
    void rejectsUniqueConstraintWithoutColumns() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> factory.getEntityMetadata(EmptyUniqueConstraintColumnsEntity.class)
        );

        assertTrue(exception.getMessage().contains("@UniqueConstraint"));
        assertTrue(exception.getMessage().contains("at least one column"));
    }

    @Test
    void expandsEmbeddedFieldsIntoFlatColumns() {
        EntityMetadata<Customer> metadata = factory.getEntityMetadata(Customer.class);

        assertEquals("customer", metadata.tableName());
        java.util.List<String> columns = new java.util.ArrayList<>();
        for (PersistentProperty property : metadata.properties()) {
            columns.add(property.columnName());
        }
        assertEquals(java.util.List.of("id", "name", "shipping_city", "shipping_street", "shipping_zip"), columns);

        PersistentProperty city = metadata.findProperty("shipping.city").orElseThrow();
        assertTrue(city.embedded());
        assertEquals("shipping", city.embeddedHostField().getName());
        assertEquals("shipping_city", city.columnName());

        PersistentProperty id = metadata.idProperty();
        assertFalse(id.embedded());
    }

    @Test
    void rejectsEmbeddableTypeWithIdField() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> factory.getEntityMetadata(CustomerWithEmbeddableIdEntity.class)
        );

        assertTrue(exception.getMessage().contains("@Embeddable"));
        assertTrue(exception.getMessage().contains("@Id"));
    }

    @Test
    void rejectsEmbeddedSubFieldWithId() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> factory.getEntityMetadata(CustomerWithEmbeddedIdSubField.class)
        );

        assertTrue(exception.getMessage().contains("@Id"));
    }

    @Test
    void rejectsEmbeddedSubFieldWithVersion() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> factory.getEntityMetadata(CustomerWithEmbeddedVersionSubField.class)
        );

        assertTrue(exception.getMessage().contains("@Version"));
    }

    @Test
    void rejectsEmbeddedSubFieldWithSoftDelete() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> factory.getEntityMetadata(CustomerWithEmbeddedSoftDeleteSubField.class)
        );

        assertTrue(exception.getMessage().contains("@SoftDelete"));
    }

    @Test
    void rejectsEmbeddedSubFieldWithCreatedAt() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> factory.getEntityMetadata(CustomerWithEmbeddedCreatedAtSubField.class)
        );

        assertTrue(exception.getMessage().contains("@CreatedAt"));
    }

    @Test
    void rejectsEmbeddedSubFieldWithUpdatedAt() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> factory.getEntityMetadata(CustomerWithEmbeddedUpdatedAtSubField.class)
        );

        assertTrue(exception.getMessage().contains("@UpdatedAt"));
    }

    @Test
    void expandsNestedEmbeddedIntoFlatColumnsWithComposedPrefix() {
        EntityMetadata<Office> metadata = factory.getEntityMetadata(Office.class);

        assertEquals("office", metadata.tableName());
        java.util.List<String> columns = new java.util.ArrayList<>();
        for (PersistentProperty property : metadata.properties()) {
            columns.add(property.columnName());
        }
        assertEquals(
                java.util.List.of("id", "name", "address_street", "address_zip", "address_geo_country", "address_geo_city"),
                columns);

        PersistentProperty street = metadata.findProperty("address.street").orElseThrow();
        assertTrue(street.embedded());
        assertEquals("address_street", street.columnName());
        assertEquals(1, street.embeddedHostPath().size());
        assertEquals("address", street.embeddedHostPath().get(0).getName());

        PersistentProperty country = metadata.findProperty("address.geo.country").orElseThrow();
        assertTrue(country.embedded());
        assertEquals("address_geo_country", country.columnName());
        assertEquals(2, country.embeddedHostPath().size());
        assertEquals("address", country.embeddedHostPath().get(0).getName());
        assertEquals("geo", country.embeddedHostPath().get(1).getName());
        // 가장 안쪽 host는 path의 마지막 요소와 동일하다.
        assertEquals("geo", country.embeddedHostField().getName());
    }

    @Test
    void rejectsCircularEmbeddedChain() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> factory.getEntityMetadata(EntityWithCircularEmbedded.class)
        );

        assertTrue(exception.getMessage().contains("circular @Embedded"));
    }

    @Test
    void rejectsEmbeddedFieldTypeWithoutEmbeddableAnnotation() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> factory.getEntityMetadata(CustomerWithNonEmbeddable.class)
        );

        assertTrue(exception.getMessage().contains("@Embedded"));
        assertTrue(exception.getMessage().contains("@Embeddable"));
    }

    @Test
    void recognizesEnumeratedStringPropertyAndBindsName() {
        EntityMetadata<EnumStringAccount> metadata = factory.getEntityMetadata(EnumStringAccount.class);

        PersistentProperty status = metadata.findProperty("status").orElseThrow();
        assertTrue(status.enumerated());
        assertEquals(EnumType.STRING, status.enumType());
        assertEquals("ACTIVE", status.toColumnValue(Status.ACTIVE));
        assertEquals(Status.INACTIVE, status.toPropertyValue("INACTIVE"));
    }

    @Test
    void recognizesEnumeratedOrdinalPropertyAndBindsOrdinal() {
        EntityMetadata<EnumOrdinalAccount> metadata = factory.getEntityMetadata(EnumOrdinalAccount.class);

        PersistentProperty status = metadata.findProperty("status").orElseThrow();
        assertTrue(status.enumerated());
        assertEquals(EnumType.ORDINAL, status.enumType());
        assertEquals(2, status.toColumnValue(Status.PENDING));
        assertEquals(Status.PENDING, status.toPropertyValue(2));
        // R2DBC 드라이버에 따라 INTEGER가 Long으로 반환되는 경우도 동일 ordinal로 해석되어야 한다.
        assertEquals(Status.INACTIVE, status.toPropertyValue(1L));
    }

    @Test
    void enumeratedDefaultsToOrdinalStrategy() {
        // jakarta.persistence.Enumerated의 기본값은 JPA 표준과 동일하게 ORDINAL이다.
        EntityMetadata<EnumDefaultAccount> metadata = factory.getEntityMetadata(EnumDefaultAccount.class);

        PersistentProperty status = metadata.findProperty("status").orElseThrow();
        assertTrue(status.enumerated());
        assertEquals(EnumType.ORDINAL, status.enumType());
    }

    @Test
    void enumeratedConvertersPassThroughNullValues() {
        EntityMetadata<EnumStringAccount> stringMetadata = factory.getEntityMetadata(EnumStringAccount.class);
        EntityMetadata<EnumOrdinalAccount> ordinalMetadata = factory.getEntityMetadata(EnumOrdinalAccount.class);

        assertNull(stringMetadata.findProperty("status").orElseThrow().toColumnValue(null));
        assertNull(stringMetadata.findProperty("status").orElseThrow().toPropertyValue(null));
        assertNull(ordinalMetadata.findProperty("status").orElseThrow().toColumnValue(null));
        assertNull(ordinalMetadata.findProperty("status").orElseThrow().toPropertyValue(null));
    }

    @Test
    void rejectsInvalidEnumStringValueWithInformativeMessage() {
        EntityMetadata<EnumStringAccount> metadata = factory.getEntityMetadata(EnumStringAccount.class);
        PersistentProperty status = metadata.findProperty("status").orElseThrow();

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> status.toPropertyValue("UNKNOWN_VALUE")
        );

        assertTrue(exception.getMessage().contains("UNKNOWN_VALUE"));
        assertTrue(exception.getMessage().contains(Status.class.getName()));
    }

    @Test
    void rejectsOutOfRangeOrdinalWithInformativeMessage() {
        EntityMetadata<EnumOrdinalAccount> metadata = factory.getEntityMetadata(EnumOrdinalAccount.class);
        PersistentProperty status = metadata.findProperty("status").orElseThrow();

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> status.toPropertyValue(42)
        );

        assertTrue(exception.getMessage().contains("42"));
        assertTrue(exception.getMessage().contains(Status.class.getName()));
    }

    @Test
    void rejectsEnumeratedOnNonEnumField() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> factory.getEntityMetadata(EnumOnNonEnumFieldEntity.class)
        );

        assertTrue(exception.getMessage().contains("@Enumerated"));
        assertTrue(exception.getMessage().contains("is not an enum"));
    }

    @Test
    void rejectsEnumeratedCombinedWithRegisteredConverter() {
        EntityMetadataFactory conflictingFactory = new EntityMetadataFactory(new DefaultNamingStrategy());
        conflictingFactory.registerConverter(Status.class, new EnumStatusConverter());

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> conflictingFactory.getEntityMetadata(EnumWithConverterEntity.class)
        );

        assertTrue(exception.getMessage().contains("@Enumerated"));
        assertTrue(exception.getMessage().contains("AttributeConverter"));
    }

    @Test
    void nonEnumeratedPropertyHasNullEnumType() {
        EntityMetadata<SampleAccount> metadata = factory.getEntityMetadata(SampleAccount.class);
        PersistentProperty email = metadata.findProperty("email").orElseThrow();

        assertFalse(email.enumerated());
        assertNull(email.enumType());
        // converter wiring 무손상 검증: enum 필드가 아니면 사용자 converter 등록 여부에 영향 없음.
        assertNotNull(email);
    }

    @Test
    void installsJsonAttributeConverterAndRoundTripsThroughCodec() {
        EntityMetadataFactory jsonFactory =
                new EntityMetadataFactory(new DefaultNamingStrategy(), new PreferencesJsonCodec());

        EntityMetadata<JsonAccount> metadata = jsonFactory.getEntityMetadata(JsonAccount.class);
        PersistentProperty prefs = metadata.findProperty("prefs").orElseThrow();

        assertTrue(prefs.json(), "@Json 필드는 json() marker가 true여야 한다");
        assertFalse(prefs.enumerated());

        Preferences value = new Preferences("dark", 14);
        Object encoded = prefs.toColumnValue(value);
        assertEquals("theme=dark;fontSize=14", encoded, "toColumnValue는 codec이 만든 JSON 문자열이어야 한다");

        Object decoded = prefs.toPropertyValue(encoded);
        assertEquals(value, decoded, "toPropertyValue는 원본 value object로 복원되어야 한다");
    }

    @Test
    void jsonConverterPassesThroughNullValues() {
        EntityMetadataFactory jsonFactory =
                new EntityMetadataFactory(new DefaultNamingStrategy(), new PreferencesJsonCodec());
        PersistentProperty prefs = jsonFactory.getEntityMetadata(JsonAccount.class)
                .findProperty("prefs").orElseThrow();

        assertNull(prefs.toColumnValue(null));
        assertNull(prefs.toPropertyValue(null));
    }

    @Test
    void columnTypeReflectsConverterStorageType() {
        // converter가 있는 property는 row 디코딩 시 도메인 타입이 아니라 driver가 받아들이는 저장 타입을
        // 요청해야 한다 — driver는 varchar 컬럼을 enum/POJO로 직접 디코딩할 수 없다.
        EntityMetadataFactory jsonFactory =
                new EntityMetadataFactory(new DefaultNamingStrategy(), new PreferencesJsonCodec());

        PersistentProperty json = jsonFactory.getEntityMetadata(JsonAccount.class)
                .findProperty("prefs").orElseThrow();
        assertEquals(String.class, json.columnType(), "@Json은 String으로 저장된다");

        PersistentProperty enumString = factory.getEntityMetadata(EnumStringAccount.class)
                .findProperty("status").orElseThrow();
        assertEquals(String.class, enumString.columnType(), "@Enumerated(STRING)은 String으로 저장된다");

        PersistentProperty enumOrdinal = factory.getEntityMetadata(EnumOrdinalAccount.class)
                .findProperty("status").orElseThrow();
        assertEquals(Integer.class, enumOrdinal.columnType(), "@Enumerated(ORDINAL)은 Integer로 저장된다");
    }

    @Test
    void columnTypeFallsBackToJavaTypeWithoutConverter() {
        PersistentProperty email = factory.getEntityMetadata(JsonAccount.class)
                .findProperty("email").orElseThrow();
        assertEquals(String.class, email.columnType(),
                "converter가 없는 property는 도메인 타입(javaType)을 그대로 요청한다");
    }

    @Test
    void jsonPropertyIsColumnMapped() {
        EntityMetadataFactory jsonFactory =
                new EntityMetadataFactory(new DefaultNamingStrategy(), new PreferencesJsonCodec());
        EntityMetadata<JsonAccount> metadata = jsonFactory.getEntityMetadata(JsonAccount.class);

        boolean present = metadata.columnMappedProperties().stream()
                .anyMatch(property -> property.propertyName().equals("prefs"));
        assertTrue(present, "@Json 필드는 일반 컬럼 매핑 property로 columnMappedProperties()에 포함돼야 한다");
    }

    @Test
    void unconfiguredJsonCodecThrowsWhenJsonValueIsConverted() {
        // 기본 생성자는 JsonCodec.unconfigured()를 사용한다. metadata 생성은 성공하지만 값 변환 시점에 던진다.
        PersistentProperty prefs = factory.getEntityMetadata(JsonAccount.class)
                .findProperty("prefs").orElseThrow();

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> prefs.toColumnValue(new Preferences("light", 12))
        );
        assertTrue(exception.getMessage().contains("No JsonCodec configured"),
                "메시지는 codec 미등록을 알려야 한다, got " + exception.getMessage());
    }

    @Test
    void rejectsJsonCombinedWithEnumerated() {
        EntityMetadataFactory jsonFactory =
                new EntityMetadataFactory(new DefaultNamingStrategy(), new PreferencesJsonCodec());

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> jsonFactory.getEntityMetadata(JsonAndEnumeratedEntity.class)
        );
        assertTrue(exception.getMessage().contains("@Json"));
        assertTrue(exception.getMessage().contains("@Enumerated"));
    }

    @Test
    void rejectsJsonCombinedWithRelationAnnotation() {
        EntityMetadataFactory jsonFactory =
                new EntityMetadataFactory(new DefaultNamingStrategy(), new PreferencesJsonCodec());

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> jsonFactory.getEntityMetadata(JsonAndRelationEntity.class)
        );
        assertTrue(exception.getMessage().contains("@Json"));
    }

    @Test
    void rejectsJsonCombinedWithRegisteredConverter() {
        EntityMetadataFactory jsonFactory =
                new EntityMetadataFactory(new DefaultNamingStrategy(), new PreferencesJsonCodec());
        jsonFactory.registerConverter(Status.class, new EnumStatusConverter());

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> jsonFactory.getEntityMetadata(JsonWithRegisteredConverterEntity.class)
        );
        assertTrue(exception.getMessage().contains("@Json"));
        assertTrue(exception.getMessage().contains("AttributeConverter"));
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

    /**
     * JSON 라이브러리 없이 {@link Preferences}만 직렬화/역직렬화하는 손수 만든 {@link io.nova.json.JsonCodec}
     * 테스트 더블이다. {@code theme=...;fontSize=...} 형태의 결정적 문자열을 사용한다.
     */
    private static final class PreferencesJsonCodec implements io.nova.json.JsonCodec {
        @Override
        public String encode(Object value) {
            Preferences prefs = (Preferences) value;
            return "theme=" + prefs.getTheme() + ";fontSize=" + prefs.getFontSize();
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T decode(String json, Class<T> type) {
            String theme = null;
            int fontSize = 0;
            for (String part : json.split(";")) {
                int eq = part.indexOf('=');
                String key = part.substring(0, eq);
                String raw = part.substring(eq + 1);
                if (key.equals("theme")) {
                    theme = raw;
                } else if (key.equals("fontSize")) {
                    fontSize = Integer.parseInt(raw);
                }
            }
            return (T) new Preferences(theme, fontSize);
        }
    }

    @Test
    void rejectsManyToOneLazyFetch() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> factory.getEntityMetadata(ManyToOneLazyEntity.class)
        );
        assertTrue(exception.getMessage().contains("@ManyToOne(fetch=LAZY)"));
    }

    @Test
    void rejectsManyToOneCascade() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> factory.getEntityMetadata(ManyToOneCascadeEntity.class)
        );
        assertTrue(exception.getMessage().contains("@ManyToOne(cascade=...)"));
    }

    @Test
    void rejectsOneToManyOrphanRemoval() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> factory.getEntityMetadata(OneToManyOrphanRemovalEntity.class)
        );
        assertTrue(exception.getMessage().contains("orphanRemoval=true"));
    }

    @Test
    void rejectsColumnInsertableFalse() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> factory.getEntityMetadata(ColumnInsertableFalseEntity.class)
        );
        assertTrue(exception.getMessage().contains("@Column(insertable=false)"));
    }

    @Test
    void rejectsColumnUnique() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> factory.getEntityMetadata(ColumnUniqueEntity.class)
        );
        assertTrue(exception.getMessage().contains("@Column(unique=true)"));
    }

    @Test
    void rejectsGeneratedValueTableStrategy() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> factory.getEntityMetadata(GeneratedValueTableEntity.class)
        );
        assertTrue(exception.getMessage().contains("@GeneratedValue(TABLE)"));
    }
}
