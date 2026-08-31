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
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

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

    private PersistentProperty property(Class<?> entityType, String propertyName) {
        return factory.getEntityMetadata(entityType).findProperty(propertyName).orElseThrow();
    }
}
