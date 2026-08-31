package io.nova.metadata;

import io.nova.support.fixtures.FixtureEntities.DecimalCollectionEntity;
import io.nova.support.fixtures.FixtureEntities.DecimalConvertedEmbeddedEntity;
import io.nova.support.fixtures.FixtureEntities.DecimalEmbeddedEntity;
import io.nova.support.fixtures.FixtureEntities.DecimalFieldEntity;
import io.nova.support.fixtures.FixtureEntities.DecimalInheritanceChild;
import io.nova.support.fixtures.FixtureEntities.DecimalInheritanceRoot;
import io.nova.support.fixtures.FixtureEntities.DecimalPropertyEntity;
import io.nova.support.fixtures.FixtureEntities.DecimalRelationEntity;
import io.nova.support.fixtures.FixtureEntities.DecimalSecondaryEntity;
import io.nova.support.fixtures.FixtureEntities.DecimalTargetEntity;
import io.nova.support.fixtures.FixtureEntities;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntityMetadataFactoryBigDecimalDdlTest {
    private final EntityMetadataFactory factory = new EntityMetadataFactory(new DefaultNamingStrategy());
    private static final ColumnStorage DECIMAL_STORAGE = new ColumnStorage(BigDecimal.class, 255, 31, 11);

    @Test
    void preservesBigDecimalStorageTypeForFieldPropertyAndEmbeddedShapes() {
        assertEquals(BigDecimal.class, property(DecimalFieldEntity.class, "id").columnType());
        assertEquals(BigDecimal.class, property(DecimalFieldEntity.class, "amount").columnType());
        assertEquals(BigDecimal.class, property(DecimalPropertyEntity.class, "id").columnType());
        assertEquals(BigDecimal.class, property(DecimalPropertyEntity.class, "amount").columnType());
        assertEquals(BigDecimal.class, property(DecimalEmbeddedEntity.class, "value.amount").columnType());
        assertEquals("overridden_amount", property(DecimalEmbeddedEntity.class, "value.amount").columnName());
        assertEquals(new ColumnStorage(BigDecimal.class, 255, 29, 7),
                ColumnStorage.from(property(DecimalEmbeddedEntity.class, "value.amount")));
        assertEquals(DECIMAL_STORAGE, ColumnStorage.from(property(DecimalFieldEntity.class, "amount")));
        assertEquals(DECIMAL_STORAGE, ColumnStorage.from(property(DecimalPropertyEntity.class, "amount")));
    }

    @Test
    void preservesBigDecimalStorageTypeForListMapAndEmbeddableConverter() {
        ElementCollectionInfo list = property(DecimalCollectionEntity.class, "amounts").elementCollectionInfo();
        ElementCollectionInfo map = property(DecimalCollectionEntity.class, "amountsByKey").elementCollectionInfo();
        PersistentProperty converted = property(DecimalConvertedEmbeddedEntity.class, "value.amount");

        assertEquals(BigDecimal.class, list.valueStorage().javaType());
        assertEquals(BigDecimal.class, map.valueStorage().javaType());
        assertEquals(BigDecimal.class, map.mapKey().keyStorage().javaType());
        assertEquals(DECIMAL_STORAGE, list.valueStorage());
        assertEquals(DECIMAL_STORAGE, map.valueStorage());
        assertEquals(DECIMAL_STORAGE, map.mapKey().keyStorage());
        assertEquals(BigDecimal.class, list.toCollectionTableDefinition(
                ColumnStorage.from(factory.getEntityMetadata(DecimalCollectionEntity.class).idProperty()))
                .ownerForeignKeyStorage().javaType());
        assertEquals(String.class, converted.columnType());

        BigDecimal exact = new BigDecimal("12345678901234567890.012345678900");
        assertEquals(exact, converted.toPropertyValue(converted.toColumnValue(exact)));
    }

    @Test
    void preservesBigDecimalStorageTypeForIdsForeignKeysJoinSecondaryAndInheritance() {
        EntityMetadata<DecimalTargetEntity> target = factory.getEntityMetadata(DecimalTargetEntity.class);
        EntityMetadata<DecimalRelationEntity> relation = factory.getEntityMetadata(DecimalRelationEntity.class);
        PersistentProperty many = relation.findProperty("manyTarget").orElseThrow();
        PersistentProperty one = relation.findProperty("oneTarget").orElseThrow();
        PersistentProperty manyToMany = relation.findProperty("targets").orElseThrow();
        JoinTableDefinition join = JoinTableDefinition.of(relation, manyToMany.manyToManyInfo(), target);

        assertEquals(DECIMAL_STORAGE, ColumnStorage.from(target.idProperty()));
        assertEquals(DECIMAL_STORAGE, ColumnStorage.from(many));
        assertEquals(DECIMAL_STORAGE, ColumnStorage.from(one));
        assertEquals(DECIMAL_STORAGE, join.targetForeignKeyColumns().get(0).storage());
        assertEquals(DECIMAL_STORAGE,
                ColumnStorage.from(factory.getEntityMetadata(DecimalSecondaryEntity.class).idProperty()));
        assertEquals(DECIMAL_STORAGE,
                ColumnStorage.from(factory.getEntityMetadata(DecimalInheritanceRoot.class).idProperty()));
        assertEquals(DECIMAL_STORAGE,
                ColumnStorage.from(factory.getEntityMetadata(DecimalInheritanceChild.class).idProperty()));
    }

    @Test
    void outerEmbeddedCollectionAndMapKeyOverridesPreserveFieldAndPropertyDecimalShapes() {
        assertEmbeddedCollectionStorage(FieldOverrideCollection.class, "values",
                new ColumnStorage(BigDecimal.class, 255, 27, 8));
        assertEmbeddedCollectionStorage(PropertyOverrideCollection.class, "values",
                new ColumnStorage(BigDecimal.class, 255, 26, 7));
        assertMapKeyStorage(FieldOverrideMap.class,
                new ColumnStorage(BigDecimal.class, 255, 25, 6));
        assertMapKeyStorage(PropertyOverrideMap.class,
                new ColumnStorage(BigDecimal.class, 255, 24, 5));
    }

    @Test
    void rejectsColumnDefinitionsThatWouldReplaceReferencedOrCollectionStorageShapes() {
        IllegalArgumentException id = assertThrows(IllegalArgumentException.class,
                () -> factory.getEntityMetadata(ColumnDefinitionDecimalId.class));
        assertTrue(id.getMessage().contains("@Column(columnDefinition)"), id.getMessage());

        IllegalArgumentException join = assertThrows(IllegalArgumentException.class,
                () -> factory.getEntityMetadata(ColumnDefinitionJoin.class));
        assertTrue(join.getMessage().contains("@JoinColumn(columnDefinition)"), join.getMessage());

        IllegalArgumentException collection = assertThrows(IllegalArgumentException.class,
                () -> factory.getEntityMetadata(ColumnDefinitionCollection.class));
        assertTrue(collection.getMessage().contains("@JoinColumn(columnDefinition)"), collection.getMessage());
    }

    @Test
    void embeddedIdConverterStorageIsSharedByToOneAndMapsId() {
        ColumnStorage expected = new ColumnStorage(String.class, 41, 0, 0);
        EntityMetadata<ConvertedEmbeddedIdTarget> target = factory.getEntityMetadata(ConvertedEmbeddedIdTarget.class);
        PersistentProperty toOne = property(ConvertedEmbeddedIdReference.class, "target");
        PersistentProperty mapsId = property(ConvertedEmbeddedIdMapsId.class, "target");

        assertEquals(expected, ColumnStorage.from(target.idProperty()));
        assertEquals(expected, ColumnStorage.from(toOne));
        assertEquals(expected, ColumnStorage.from(mapsId));
    }

    @Test
    void collectionDefinitionsRequireCompleteColumnStorageRatherThanLossyClassOverload() {
        assertFalse(Arrays.stream(ElementCollectionInfo.class.getMethods())
                .anyMatch(method -> method.getName().equals("toCollectionTableDefinition")
                        && Arrays.equals(method.getParameterTypes(), new Class<?>[]{Class.class})));
        ElementCollectionInfo info = property(DecimalCollectionEntity.class, "amounts").elementCollectionInfo();
        ColumnStorage owner = ColumnStorage.from(factory.getEntityMetadata(DecimalCollectionEntity.class).idProperty());
        assertEquals(owner, info.toCollectionTableDefinition(owner).ownerForeignKeyStorage());
    }

    private void assertEmbeddedCollectionStorage(Class<?> entityType, String propertyName, ColumnStorage expected) {
        ElementCollectionInfo info = property(entityType, propertyName).elementCollectionInfo();
        assertEquals(expected, info.embeddableColumns().get(0).storage());
    }

    private void assertMapKeyStorage(Class<?> entityType, ColumnStorage expected) {
        ElementCollectionInfo info = property(entityType, "values").elementCollectionInfo();
        assertEquals(expected, info.mapKey().embeddableKeyColumns().get(0).storage());
    }

    private PersistentProperty property(Class<?> entityType, String propertyName) {
        return factory.getEntityMetadata(entityType).findProperty(propertyName).orElseThrow();
    }

    @jakarta.persistence.Embeddable
    static class DecimalComponent {
        @jakarta.persistence.Column(name = "amount", precision = 9, scale = 2)
        BigDecimal amount;
    }

    @jakarta.persistence.Entity
    static class FieldOverrideCollection {
        @jakarta.persistence.Id Long id;
        @jakarta.persistence.ElementCollection
        @jakarta.persistence.AttributeOverride(name = "amount",
                column = @jakarta.persistence.Column(name = "field_amount", precision = 27, scale = 8))
        List<DecimalComponent> values;
    }

    @jakarta.persistence.Entity
    static class PropertyOverrideCollection {
        Long id;
        List<DecimalComponent> values;

        @jakarta.persistence.Id
        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        @jakarta.persistence.ElementCollection
        @jakarta.persistence.AttributeOverride(name = "amount",
                column = @jakarta.persistence.Column(name = "property_amount", precision = 26, scale = 7))
        public List<DecimalComponent> getValues() {
            return values;
        }

        public void setValues(List<DecimalComponent> values) {
            this.values = values;
        }
    }

    @jakarta.persistence.Entity
    static class FieldOverrideMap {
        @jakarta.persistence.Id Long id;
        @jakarta.persistence.ElementCollection
        @jakarta.persistence.MapKeyClass(DecimalComponent.class)
        @jakarta.persistence.AttributeOverride(name = "key.amount",
                column = @jakarta.persistence.Column(name = "field_key", precision = 25, scale = 6))
        Map<DecimalComponent, String> values;
    }

    @jakarta.persistence.Entity
    static class PropertyOverrideMap {
        Long id;
        Map<DecimalComponent, String> values;

        @jakarta.persistence.Id
        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        @jakarta.persistence.ElementCollection
        @jakarta.persistence.MapKeyClass(DecimalComponent.class)
        @jakarta.persistence.AttributeOverride(name = "key.amount",
                column = @jakarta.persistence.Column(name = "property_key", precision = 24, scale = 5))
        public Map<DecimalComponent, String> getValues() {
            return values;
        }

        public void setValues(Map<DecimalComponent, String> values) {
            this.values = values;
        }
    }

    @jakarta.persistence.Entity
    static class ColumnDefinitionDecimalId {
        @jakarta.persistence.Id
        @jakarta.persistence.Column(columnDefinition = "numeric(31, 11)")
        BigDecimal id;
    }

    @jakarta.persistence.Entity
    static class ColumnDefinitionTarget {
        @jakarta.persistence.Id
        @jakarta.persistence.Column(precision = 31, scale = 11)
        BigDecimal id;
    }

    @jakarta.persistence.Entity
    static class ColumnDefinitionJoin {
        @jakarta.persistence.Id Long id;
        @jakarta.persistence.ManyToOne
        @jakarta.persistence.JoinColumn(columnDefinition = "numeric(31, 11)")
        ColumnDefinitionTarget target;
    }

    @jakarta.persistence.Entity
    static class ColumnDefinitionCollection {
        @jakarta.persistence.Id
        @jakarta.persistence.Column(precision = 31, scale = 11)
        BigDecimal id;
        @jakarta.persistence.ElementCollection
        @jakarta.persistence.CollectionTable(joinColumns =
                @jakarta.persistence.JoinColumn(columnDefinition = "numeric(31, 11)"))
        List<String> values;
    }

    @jakarta.persistence.Embeddable
    static class ConvertedDecimalId {
        @jakarta.persistence.Convert(converter = FixtureEntities.DecimalStringConverter.class)
        @jakarta.persistence.Column(name = "id", length = 41)
        BigDecimal value;
    }

    @jakarta.persistence.Entity
    static class ConvertedEmbeddedIdTarget {
        @jakarta.persistence.EmbeddedId ConvertedDecimalId id;
    }

    @jakarta.persistence.Entity
    static class ConvertedEmbeddedIdReference {
        @jakarta.persistence.Id Long id;
        @jakarta.persistence.ManyToOne
        @jakarta.persistence.JoinColumn(name = "target_id")
        ConvertedEmbeddedIdTarget target;
    }

    @jakarta.persistence.Entity
    static class ConvertedEmbeddedIdMapsId {
        @jakarta.persistence.EmbeddedId ConvertedDecimalId id;
        @jakarta.persistence.OneToOne
        @jakarta.persistence.MapsId
        @jakarta.persistence.JoinColumn(name = "id")
        ConvertedEmbeddedIdTarget target;
    }
}
