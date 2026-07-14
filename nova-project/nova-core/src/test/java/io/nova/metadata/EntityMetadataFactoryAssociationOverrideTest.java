package io.nova.metadata;

import io.nova.support.fixtures.FixtureEntities.AssocCity;
import io.nova.support.fixtures.FixtureEntities.AssocCountry;
import io.nova.support.fixtures.FixtureEntities.AssocOverrideEmbeddedPath;
import io.nova.support.fixtures.FixtureEntities.AssocOverrideScalarTarget;
import io.nova.support.fixtures.FixtureEntities.AssocOverrideUnknownName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link EntityMetadataFactory}가 서브클래스의 {@code @AssociationOverride}로 {@code @MappedSuperclass}에서
 * 상속한 to-one 관계의 join 컬럼을 재지정하고, 잘못된 대상(존재하지 않음/관계 아님/embedded path)을
 * fail-fast로 거부하는지 검증한다.
 */
class EntityMetadataFactoryAssociationOverrideTest {
    private final EntityMetadataFactory factory = new EntityMetadataFactory(new DefaultNamingStrategy());

    @Test
    void associationOverrideRemapsInheritedToOneJoinColumn() {
        EntityMetadata<AssocCity> metadata = factory.getEntityMetadata(AssocCity.class);

        List<PersistentProperty> relations = metadata.manyToOneProperties();
        assertEquals(1, relations.size());
        PersistentProperty country = relations.get(0);
        assertEquals("country", country.propertyName());
        assertEquals("home_country_id", country.columnName(),
                "@AssociationOverride의 @JoinColumn.name이 상속한 FK 컬럼명을 재지정해야 한다");
        assertTrue(country.manyToOne());
        assertSame(AssocCountry.class, country.manyToOneTargetType());

        // 재지정된 컬럼명이 실제 컬럼 매핑 집합에도 반영돼야 한다(원래 이름은 사라진다).
        boolean hasRemapped = metadata.columnMappedProperties().stream()
                .anyMatch(p -> p.columnName().equals("home_country_id"));
        boolean hasOriginal = metadata.columnMappedProperties().stream()
                .anyMatch(p -> p.columnName().equals("region_country_id"));
        assertTrue(hasRemapped, "재지정된 컬럼명이 매핑돼야 한다");
        assertTrue(!hasOriginal, "원래 상속 FK 컬럼명은 더 이상 매핑되지 않아야 한다");
    }

    @Test
    void associationOverrideOnUnknownPropertyFailsFast() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> factory.getEntityMetadata(AssocOverrideUnknownName.class));
        assertTrue(ex.getMessage().contains("does not match any property"),
                "메시지: " + ex.getMessage());
    }

    @Test
    void associationOverrideOnScalarPropertyFailsFast() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> factory.getEntityMetadata(AssocOverrideScalarTarget.class));
        assertTrue(ex.getMessage().contains("must target an owning"),
                "메시지: " + ex.getMessage());
    }

    @Test
    void associationOverrideOnEmbeddedPathFailsFast() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> factory.getEntityMetadata(AssocOverrideEmbeddedPath.class));
        assertTrue(ex.getMessage().contains("embedded"),
                "메시지: " + ex.getMessage());
    }
}
