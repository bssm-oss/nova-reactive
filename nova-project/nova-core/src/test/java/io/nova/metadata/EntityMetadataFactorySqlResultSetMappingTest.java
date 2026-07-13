package io.nova.metadata;

import jakarta.persistence.ColumnResult;
import jakarta.persistence.ConstructorResult;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityResult;
import jakarta.persistence.FieldResult;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.SqlResultSetMapping;
import jakarta.persistence.SqlResultSetMappings;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link EntityMetadataFactory#sqlResultSetMappings(Class)}의 {@code @SqlResultSetMapping} 파싱 단위 테스트 —
 * entity/constructor/column 매핑, 반복 선언, {@code @MappedSuperclass} 상속, 구조적 미지원 요소 fail-fast.
 */
class EntityMetadataFactorySqlResultSetMappingTest {

    private final EntityMetadataFactory factory = new EntityMetadataFactory(new DefaultNamingStrategy());

    @Test
    void parsesEntityResultWithFieldAliases() {
        List<SqlResultSetMappingDefinition> mappings = factory.sqlResultSetMappings(WithEntityResult.class);
        assertEquals(1, mappings.size());
        SqlResultSetMappingDefinition mapping = mappings.get(0);
        assertEquals("entityMapping", mapping.name());
        assertEquals(1, mapping.entities().size());
        SqlResultSetMappingDefinition.EntityMapping entity = mapping.entities().get(0);
        assertEquals(Widget.class, entity.entityClass());
        assertEquals("n", entity.fieldAliases().get("name"));
        assertEquals(1, entity.fieldAliases().size());
        assertEquals(1, mapping.resultElementCount());
    }

    @Test
    void parsesConstructorResult() {
        List<SqlResultSetMappingDefinition> mappings = factory.sqlResultSetMappings(WithConstructorResult.class);
        assertEquals(1, mappings.size());
        SqlResultSetMappingDefinition mapping = mappings.get(0);
        assertEquals(1, mapping.classes().size());
        SqlResultSetMappingDefinition.ConstructorMapping ctor = mapping.classes().get(0);
        assertEquals(WidgetView.class, ctor.targetClass());
        assertEquals(2, ctor.columns().size());
        assertEquals("w_name", ctor.columns().get(0).column());
        assertNull(ctor.columns().get(0).type());
        assertEquals("w_price", ctor.columns().get(1).column());
        assertEquals(java.math.BigDecimal.class, ctor.columns().get(1).type());
    }

    @Test
    void parsesColumnResult() {
        List<SqlResultSetMappingDefinition> mappings = factory.sqlResultSetMappings(WithColumnResult.class);
        assertEquals(1, mappings.size());
        SqlResultSetMappingDefinition mapping = mappings.get(0);
        assertEquals(1, mapping.columns().size());
        assertEquals("total", mapping.columns().get(0).column());
        assertEquals(Long.class, mapping.columns().get(0).type());
    }

    @Test
    void parsesMixedAndRepeatedMappings() {
        List<SqlResultSetMappingDefinition> mappings = factory.sqlResultSetMappings(WithMultipleMappings.class);
        assertEquals(2, mappings.size());
        assertEquals("mixed", mappings.get(0).name());
        assertEquals(2, mappings.get(0).resultElementCount());
        assertEquals("scalarOnly", mappings.get(1).name());
    }

    @Test
    void inheritsMappingsFromMappedSuperclass() {
        List<SqlResultSetMappingDefinition> mappings = factory.sqlResultSetMappings(ChildEntity.class);
        assertEquals(1, mappings.size());
        assertEquals("inheritedMapping", mappings.get(0).name());
    }

    @Test
    void rejectsEmptyMapping() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> factory.sqlResultSetMappings(WithEmptyMapping.class));
        assertTrue(ex.getMessage().contains("at least one"));
    }

    @Test
    void rejectsDiscriminatorColumn() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> factory.sqlResultSetMappings(WithDiscriminator.class));
        assertTrue(ex.getMessage().contains("discriminatorColumn"));
    }

    @Test
    void rejectsBlankMappingName() {
        assertThrows(IllegalStateException.class, () -> factory.sqlResultSetMappings(WithBlankName.class));
    }

    // ------------------------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------------------------

    @Entity
    static class Widget {
        @Id
        Long id;
        String name;
        java.math.BigDecimal price;
    }

    static class WidgetView {
        WidgetView(String name, java.math.BigDecimal price) {
        }
    }

    @Entity
    @SqlResultSetMapping(name = "entityMapping",
            entities = @EntityResult(entityClass = Widget.class,
                    fields = @FieldResult(name = "name", column = "n")))
    static class WithEntityResult {
        @Id
        Long id;
    }

    @Entity
    @SqlResultSetMapping(name = "ctorMapping",
            classes = @ConstructorResult(targetClass = WidgetView.class,
                    columns = {@ColumnResult(name = "w_name"),
                            @ColumnResult(name = "w_price", type = java.math.BigDecimal.class)}))
    static class WithConstructorResult {
        @Id
        Long id;
    }

    @Entity
    @SqlResultSetMapping(name = "columnMapping", columns = @ColumnResult(name = "total", type = Long.class))
    static class WithColumnResult {
        @Id
        Long id;
    }

    @Entity
    @SqlResultSetMappings({
            @SqlResultSetMapping(name = "mixed",
                    entities = @EntityResult(entityClass = Widget.class),
                    columns = @ColumnResult(name = "extra")),
            @SqlResultSetMapping(name = "scalarOnly", columns = @ColumnResult(name = "c"))
    })
    static class WithMultipleMappings {
        @Id
        Long id;
    }

    @MappedSuperclass
    @SqlResultSetMapping(name = "inheritedMapping", columns = @ColumnResult(name = "c"))
    static class ParentEntity {
        @Id
        Long id;
    }

    @Entity
    static class ChildEntity extends ParentEntity {
        String name;
    }

    @Entity
    @SqlResultSetMapping(name = "empty")
    static class WithEmptyMapping {
        @Id
        Long id;
    }

    @Entity
    @SqlResultSetMapping(name = "disc",
            entities = @EntityResult(entityClass = Widget.class, discriminatorColumn = "dtype"))
    static class WithDiscriminator {
        @Id
        Long id;
    }

    @Entity
    @SqlResultSetMapping(name = "", columns = @ColumnResult(name = "c"))
    static class WithBlankName {
        @Id
        Long id;
    }
}
