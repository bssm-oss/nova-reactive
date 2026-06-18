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
    void acceptsJoinedStrategyAndCarriesRootTableInfo() {
        EntityMetadata<JoinedVehicle> root = factory.getEntityMetadata(JoinedVehicle.class);
        assertTrue(root.hasInheritance());
        assertTrue(root.inheritance().joined());
        assertEquals("joined_vehicle", root.inheritance().rootTableName());
        assertEquals("id", root.inheritance().rootIdColumn());
        EntityMetadata<JoinedCar> car = factory.getEntityMetadata(JoinedCar.class);
        assertEquals("joined_car", car.tableName());
        assertTrue(car.inheritance().joined());
    }

    @Test
    void joinedLayoutSplitsRootAndSubtypeColumns() {
        factory.getEntityMetadata(JoinedCar.class);
        factory.getEntityMetadata(JoinedTruck.class);
        InheritanceLayout layout = factory.inheritanceLayout(JoinedVehicle.class);
        List<String> rootColumns = layout.rootTableColumns().stream()
                .map(PersistentProperty::columnName).toList();
        assertTrue(rootColumns.contains("id"), "루트 테이블은 id를 가진다");
        assertTrue(rootColumns.contains("name"), "루트 테이블은 공통 컬럼 name을 가진다");
        assertFalse(rootColumns.contains("doors"), "서브타입 전용 컬럼은 루트 테이블에 없다");
        InheritanceLayout.ConcreteSubtype car = layout.subtypes().stream()
                .filter(s -> s.metadata().entityType() == JoinedCar.class).findFirst().orElseThrow();
        List<String> carOwn = car.ownTableColumns().stream()
                .map(PersistentProperty::columnName).toList();
        assertEquals("id", carOwn.get(0), "서브타입 테이블 첫 컬럼은 FK PK(id)");
        assertTrue(carOwn.contains("doors"), "서브타입 테이블은 자기 컬럼 doors를 가진다");
        assertFalse(carOwn.contains("name"), "공통 컬럼은 서브타입 테이블에 중복되지 않는다");
    }

    @Test
    void acceptsTablePerClassStrategyWithFullColumnTables() {
        factory.getEntityMetadata(TpcCar.class);
        factory.getEntityMetadata(TpcTruck.class);
        EntityMetadata<TpcCar> car = factory.getEntityMetadata(TpcCar.class);
        assertTrue(car.inheritance().tablePerClass());
        assertEquals("tpc_car", car.tableName());
        List<String> columns = car.columnMappedProperties().stream()
                .map(PersistentProperty::columnName).toList();
        assertTrue(columns.contains("id"), "TPC 테이블은 상속한 id를 가진다");
        assertTrue(columns.contains("name"), "TPC 테이블은 상속한 name을 가진다");
        assertTrue(columns.contains("doors"), "TPC 테이블은 자기 컬럼 doors를 가진다");
        InheritanceLayout layout = factory.inheritanceLayout(TpcVehicle.class);
        assertTrue(layout.rootTableColumns().isEmpty(), "TPC는 공유 루트 테이블 컬럼이 없다");
        assertEquals(2, layout.subtypes().size(), "두 구체 서브타입이 등록된다");
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
    @Table(name = "joined_vehicle")
    @Inheritance(strategy = InheritanceType.JOINED)
    @DiscriminatorColumn(name = "kind", discriminatorType = DiscriminatorType.STRING)
    abstract static class JoinedVehicle {
        @Id
        Long id;
        String name;
    }

    @Entity
    @Table(name = "joined_car")
    @DiscriminatorValue("CAR")
    static class JoinedCar extends JoinedVehicle {
        int doors;
    }

    @Entity
    @Table(name = "joined_truck")
    @DiscriminatorValue("TRUCK")
    static class JoinedTruck extends JoinedVehicle {
        double payload;
    }

    @Entity
    @Table(name = "tpc_vehicle")
    @Inheritance(strategy = InheritanceType.TABLE_PER_CLASS)
    @DiscriminatorColumn(name = "kind", discriminatorType = DiscriminatorType.STRING)
    abstract static class TpcVehicle {
        @Id
        Long id;
        String name;
    }

    @Entity
    @Table(name = "tpc_car")
    @DiscriminatorValue("CAR")
    static class TpcCar extends TpcVehicle {
        int doors;
    }

    @Entity
    @Table(name = "tpc_truck")
    @DiscriminatorValue("TRUCK")
    static class TpcTruck extends TpcVehicle {
        double payload;
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
