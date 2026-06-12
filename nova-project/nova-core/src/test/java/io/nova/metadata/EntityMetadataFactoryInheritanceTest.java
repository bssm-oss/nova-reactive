package io.nova.metadata;

import jakarta.persistence.DiscriminatorColumn;
import jakarta.persistence.DiscriminatorType;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Inheritance;
import jakarta.persistence.InheritanceType;
import jakarta.persistence.Table;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SINGLE_TABLE 상속 메타데이터 추출/거부 규칙을 보호한다.
 */
class EntityMetadataFactoryInheritanceTest {
    private final EntityMetadataFactory factory = new EntityMetadataFactory(new DefaultNamingStrategy());

    @Test
    void rootCarriesDiscriminatorMetadataWithJpaDefaults() {
        EntityMetadata<Animal> root = factory.getEntityMetadata(Animal.class);
        assertTrue(root.hasInheritance());
        assertTrue(root.isInheritanceRoot());
        assertEquals("dtype", root.inheritance().discriminatorColumn(), "기본 discriminator 컬럼명은 dtype");
        assertEquals(DiscriminatorType.STRING, root.inheritance().discriminatorType());
        assertEquals(Animal.class, root.inheritance().root());
    }

    @Test
    void subtypeInheritsRootTableAndColumnsPlusOwnColumns() {
        EntityMetadata<Dog> dog = factory.getEntityMetadata(Dog.class);
        assertTrue(dog.hasInheritance());
        assertFalse(dog.isInheritanceRoot());
        assertEquals("animals", dog.tableName(), "서브타입 테이블은 루트의 @Table");
        assertEquals("DOG", dog.inheritance().discriminatorValue(), "@DiscriminatorValue 값을 사용");
        List<String> columns = dog.columnMappedProperties().stream()
                .map(PersistentProperty::columnName).toList();
        assertTrue(columns.contains("id"), "루트 @Id 컬럼 포함");
        assertTrue(columns.contains("name"), "루트 컬럼 포함");
        assertTrue(columns.contains("breed"), "서브타입 전용 컬럼 포함");
    }

    @Test
    void stringDiscriminatorDefaultsToEntityName() {
        // Cat은 @DiscriminatorValue가 없으므로 STRING 기본값(entity 이름)을 사용한다.
        EntityMetadata<Cat> cat = factory.getEntityMetadata(Cat.class);
        assertEquals("Cat", cat.inheritance().discriminatorValue());
    }

    @Test
    void mergedHierarchyMetadataUnionsAllSubtypeColumnsAsNullable() {
        // 서브타입들을 먼저 빌드해 레지스트리에 등록한다.
        factory.getEntityMetadata(Dog.class);
        factory.getEntityMetadata(Cat.class);
        EntityMetadata<?> merged = factory.mergedHierarchyMetadata(Animal.class);
        List<String> columns = merged.columnMappedProperties().stream()
                .map(PersistentProperty::columnName).toList();
        assertTrue(columns.contains("breed"), "Dog의 breed가 union에 포함");
        assertTrue(columns.contains("indoor"), "Cat의 indoor가 union에 포함");
        // 서브타입 전용 컬럼은 nullable로 낮춰진다.
        PersistentProperty breed = merged.columnMappedProperties().stream()
                .filter(p -> p.columnName().equals("breed")).findFirst().orElseThrow();
        assertTrue(breed.nullable(), "서브타입 전용 컬럼은 단일 테이블에서 nullable이어야 한다");
    }

    @Test
    void resolveSubtypeDispatchesByDiscriminatorValue() {
        factory.getEntityMetadata(Dog.class);
        factory.getEntityMetadata(Cat.class);
        EntityMetadata<?> root = factory.getEntityMetadata(Animal.class);
        assertEquals(Dog.class, factory.resolveSubtype(root, "DOG").entityType());
        assertEquals(Cat.class, factory.resolveSubtype(root, "Cat").entityType());
        assertThrows(IllegalStateException.class, () -> factory.resolveSubtype(root, "UNKNOWN"));
    }

    @Test
    void rejectsJoinedStrategy() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> factory.getEntityMetadata(JoinedRoot.class));
        assertTrue(error.getMessage().contains("SINGLE_TABLE"));
    }

    @Test
    void rejectsTablePerClassStrategy() {
        assertThrows(IllegalArgumentException.class,
                () -> factory.getEntityMetadata(TablePerClassRoot.class));
    }

    @Test
    void honorsCustomDiscriminatorColumnNameAndType() {
        EntityMetadata<IntRoot> root = factory.getEntityMetadata(IntRoot.class);
        assertEquals("type_code", root.inheritance().discriminatorColumn());
        assertEquals(DiscriminatorType.INTEGER, root.inheritance().discriminatorType());
    }

    @Test
    void rejectsIntegerDiscriminatorWithoutExplicitValueOnConcreteSubtype() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> factory.getEntityMetadata(IntMissingValue.class));
        assertTrue(error.getMessage().contains("@DiscriminatorValue"));
    }

    @Test
    void rejectsDuplicateDiscriminatorValue() {
        factory.getEntityMetadata(DupA.class);
        assertThrows(IllegalArgumentException.class, () -> factory.getEntityMetadata(DupB.class));
    }

    // --- fixtures -----------------------------------------------------------

    @Entity
    @Table(name = "animals")
    @Inheritance(strategy = InheritanceType.SINGLE_TABLE)
    abstract static class Animal {
        @Id
        Long id;
        String name;
    }

    @Entity
    @DiscriminatorValue("DOG")
    static class Dog extends Animal {
        String breed;
    }

    @Entity
    static class Cat extends Animal {
        boolean indoor;
    }

    @Entity
    @Inheritance(strategy = InheritanceType.JOINED)
    static class JoinedRoot {
        @Id
        Long id;
    }

    @Entity
    @Inheritance(strategy = InheritanceType.TABLE_PER_CLASS)
    static class TablePerClassRoot {
        @Id
        Long id;
    }

    @Entity
    @Table(name = "int_root")
    @Inheritance(strategy = InheritanceType.SINGLE_TABLE)
    @DiscriminatorColumn(name = "type_code", discriminatorType = DiscriminatorType.INTEGER)
    abstract static class IntRoot {
        @Id
        Long id;
    }

    @Entity
    static class IntMissingValue extends IntRoot {
        String label;
    }

    @Entity
    @Table(name = "dup")
    @Inheritance(strategy = InheritanceType.SINGLE_TABLE)
    abstract static class DupRoot {
        @Id
        Long id;
    }

    @Entity
    @DiscriminatorValue("SAME")
    static class DupA extends DupRoot {
    }

    @Entity
    @DiscriminatorValue("SAME")
    static class DupB extends DupRoot {
    }
}
