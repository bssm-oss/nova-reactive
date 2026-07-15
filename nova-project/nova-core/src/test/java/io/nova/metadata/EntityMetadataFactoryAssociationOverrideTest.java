package io.nova.metadata;

import io.nova.support.fixtures.FixtureEntities.AssocCity;
import io.nova.support.fixtures.FixtureEntities.AssocCountry;
import io.nova.support.fixtures.FixtureEntities.AssocMidCity;
import io.nova.support.fixtures.FixtureEntities.AssocOverrideColumnCollision;
import io.nova.support.fixtures.FixtureEntities.AssocOverrideEmbeddedPath;
import io.nova.support.fixtures.FixtureEntities.AssocOverrideScalarTarget;
import io.nova.support.fixtures.FixtureEntities.AssocOverrideUnknownName;
import io.nova.support.fixtures.FixtureEntities.AssocSubCity;
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
    void associationOverrideOnIntermediateMappedSuperclassRemapsInheritedJoinColumn() {
        // country 관계는 최상위 @MappedSuperclass(AssocRegionBase)에서 상속했고, 중간
        // @MappedSuperclass(AssocIntermediateBase)가 그 join 컬럼을 재지정한다. concrete 엔티티는 override를
        // 선언하지 않으므로, 계층 walk가 중간 @MappedSuperclass의 override를 적용해야 한다.
        EntityMetadata<AssocMidCity> metadata = factory.getEntityMetadata(AssocMidCity.class);

        List<PersistentProperty> relations = metadata.manyToOneProperties();
        assertEquals(1, relations.size());
        PersistentProperty country = relations.get(0);
        assertEquals("country", country.propertyName());
        assertEquals("mid_country_id", country.columnName(),
                "중간 @MappedSuperclass의 @AssociationOverride가 상속 FK 컬럼명을 재지정해야 한다");
        assertSame(AssocCountry.class, country.manyToOneTargetType());

        boolean hasRemapped = metadata.columnMappedProperties().stream()
                .anyMatch(p -> p.columnName().equals("mid_country_id"));
        boolean hasOriginal = metadata.columnMappedProperties().stream()
                .anyMatch(p -> p.columnName().equals("region_country_id"));
        assertTrue(hasRemapped, "중간 @MappedSuperclass가 재지정한 컬럼명이 매핑돼야 한다");
        assertTrue(!hasOriginal, "원래 상속 FK 컬럼명은 더 이상 매핑되지 않아야 한다");
    }

    @Test
    void subclassAssociationOverrideWinsOverIntermediateMappedSuperclass() {
        // 중간 @MappedSuperclass(AssocIntermediateBase)는 country를 mid_country_id로 재지정하지만, concrete
        // 서브클래스(AssocSubCity)가 같은 name을 sub_country_id로 다시 재지정한다 — 더 파생된 선언이 이겨야 한다.
        EntityMetadata<AssocSubCity> metadata = factory.getEntityMetadata(AssocSubCity.class);

        List<PersistentProperty> relations = metadata.manyToOneProperties();
        assertEquals(1, relations.size());
        PersistentProperty country = relations.get(0);
        assertEquals("sub_country_id", country.columnName(),
                "서브클래스 @AssociationOverride가 중간 @MappedSuperclass 선언을 이겨야 한다");

        boolean hasSub = metadata.columnMappedProperties().stream()
                .anyMatch(p -> p.columnName().equals("sub_country_id"));
        boolean hasMid = metadata.columnMappedProperties().stream()
                .anyMatch(p -> p.columnName().equals("mid_country_id"));
        boolean hasOriginal = metadata.columnMappedProperties().stream()
                .anyMatch(p -> p.columnName().equals("region_country_id"));
        assertTrue(hasSub, "서브클래스가 재지정한 컬럼명이 매핑돼야 한다");
        assertTrue(!hasMid, "패배한 중간 @MappedSuperclass 컬럼명은 매핑되지 않아야 한다");
        assertTrue(!hasOriginal, "원래 상속 FK 컬럼명은 매핑되지 않아야 한다");
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

    @Test
    void associationOverrideIntoExistingColumnFailsFast() {
        // override로 재지정한 FK 컬럼명이 서브클래스의 스칼라 @Column과 충돌하면, 두 property가 한 컬럼에 매핑돼
        // silent 데이터 손상이 된다 — uniqueness 게이트가 duplicate column으로 거부해야 한다.
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> factory.getEntityMetadata(AssocOverrideColumnCollision.class));
        assertTrue(ex.getMessage().contains("duplicate column"),
                "메시지: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("taken_col"),
                "충돌 컬럼명을 명시해야 한다. 메시지: " + ex.getMessage());
    }
}
