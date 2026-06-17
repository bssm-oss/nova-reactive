package io.nova.sql;

import io.nova.metadata.DefaultNamingStrategy;
import io.nova.metadata.EntityMetadata;
import io.nova.metadata.EntityMetadataFactory;
import io.nova.metadata.InheritanceLayout;
import jakarta.persistence.DiscriminatorColumn;
import jakarta.persistence.DiscriminatorType;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Inheritance;
import jakarta.persistence.InheritanceType;
import jakarta.persistence.Table;
import io.nova.query.QuerySpec;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JOINED / TABLE_PER_CLASS 멀티테이블 SQL 렌더링(SELECT JOIN/UNION, 멀티테이블 INSERT/UPDATE/DELETE)을
 * 문자열 수준에서 보호한다. driver 수용성은 별도 H2 통합 테스트가 검증한다.
 */
class AbstractSqlRendererInheritanceTest {
    private final EntityMetadataFactory factory = new EntityMetadataFactory(new DefaultNamingStrategy());
    private final Dialect dialect = new TestDialect();

    private InheritanceLayout joinedLayout() {
        factory.getEntityMetadata(JCar.class);
        factory.getEntityMetadata(JTruck.class);
        return factory.inheritanceLayout(JVehicle.class);
    }

    private InheritanceLayout tpcLayout() {
        factory.getEntityMetadata(TCar.class);
        factory.getEntityMetadata(TTruck.class);
        return factory.inheritanceLayout(TVehicle.class);
    }

    // --- JOINED ------------------------------------------------------------

    @Test
    void joinedRootInsertSkipsIdentityIdAndWritesDiscriminator() {
        EntityMetadata<JCar> car = factory.getEntityMetadata(JCar.class);
        InheritanceLayout layout = joinedLayout();
        JCar entity = new JCar();
        entity.name = "ada";
        SqlStatement statement = dialect.sqlRenderer().insertJoinedRoot(
                car, "j_vehicle", layout.rootTableColumns(), entity);
        assertEquals("insert into j_vehicle (name, kind) values (?, ?)", statement.sql());
        assertEquals(java.util.List.of("ada", "CAR"), statement.bindings());
    }

    @Test
    void joinedSubtypeInsertWritesFkIdAndOwnColumns() {
        EntityMetadata<JCar> car = factory.getEntityMetadata(JCar.class);
        InheritanceLayout layout = joinedLayout();
        InheritanceLayout.ConcreteSubtype subtype = layout.subtypes().stream()
                .filter(s -> s.metadata().entityType() == JCar.class).findFirst().orElseThrow();
        JCar entity = new JCar();
        entity.id = 7L;
        entity.doors = 4;
        SqlStatement statement = dialect.sqlRenderer().insertJoinedSubtype(car, subtype.ownTableColumns(), entity);
        assertEquals("insert into j_car (id, doors) values (?, ?)", statement.sql());
        assertEquals(java.util.List.of(7L, 4), statement.bindings());
    }

    @Test
    void joinedPolymorphicSelectJoinsAllSubtypeTables() {
        InheritanceLayout layout = joinedLayout();
        SqlStatement statement = dialect.sqlRenderer().selectJoinedPolymorphic(layout, QuerySpec.empty());
        assertEquals(
                "select j_vehicle.id as id, j_vehicle.name as name, j_car.doors as doors,"
                        + " j_truck.payload as payload, j_vehicle.kind as kind"
                        + " from j_vehicle left join j_car on j_vehicle.id = j_car.id"
                        + " left join j_truck on j_vehicle.id = j_truck.id",
                statement.sql());
        assertTrue(statement.bindings().isEmpty());
    }

    @Test
    void joinedFindByIdQualifiesRootPk() {
        InheritanceLayout layout = joinedLayout();
        SqlStatement statement = dialect.sqlRenderer().selectJoinedById(layout, 9L);
        assertTrue(statement.sql().endsWith("where j_vehicle.id = ?"), statement.sql());
        assertEquals(java.util.List.of(9L), statement.bindings());
    }

    @Test
    void joinedUpdateSplitsRootAndSubtype() {
        EntityMetadata<JCar> car = factory.getEntityMetadata(JCar.class);
        InheritanceLayout layout = joinedLayout();
        InheritanceLayout.ConcreteSubtype subtype = layout.subtypes().stream()
                .filter(s -> s.metadata().entityType() == JCar.class).findFirst().orElseThrow();
        JCar entity = new JCar();
        entity.id = 3L;
        entity.name = "bob";
        entity.doors = 2;
        SqlStatement root = dialect.sqlRenderer().updateJoinedRoot(car, "j_vehicle", layout.rootTableColumns(), entity);
        assertEquals("update j_vehicle set name = ? where id = ?", root.sql());
        assertEquals(java.util.List.of("bob", 3L), root.bindings());
        SqlStatement sub = dialect.sqlRenderer().updateJoinedSubtype(car, subtype.ownTableColumns(), entity);
        assertEquals("update j_car set doors = ? where id = ?", sub.sql());
        assertEquals(java.util.List.of(2, 3L), sub.bindings());
    }

    @Test
    void joinedDeleteRendersSubtypeAndRoot() {
        EntityMetadata<JCar> car = factory.getEntityMetadata(JCar.class);
        InheritanceLayout layout = joinedLayout();
        SqlStatement sub = dialect.sqlRenderer().deleteJoinedSubtypeById(car, 5L);
        assertEquals("delete from j_car where id = ?", sub.sql());
        SqlStatement root = dialect.sqlRenderer().deleteJoinedRootById(layout, 5L);
        assertEquals("delete from j_vehicle where id = ?", root.sql());
    }

    // --- TABLE_PER_CLASS ---------------------------------------------------

    @Test
    void tablePerClassPolymorphicSelectUnionsAlignedColumns() {
        InheritanceLayout layout = tpcLayout();
        SqlStatement statement = dialect.sqlRenderer().selectTablePerClassPolymorphic(layout, QuerySpec.empty());
        // 모든 브랜치가 동일 컬럼 순서를 갖도록 정렬되고, 없는 컬럼은 null, discriminator는 상수다.
        assertTrue(statement.sql().contains("union all"), statement.sql());
        assertTrue(statement.sql().contains("'CAR' as kind"), statement.sql());
        assertTrue(statement.sql().contains("'TRUCK' as kind"), statement.sql());
        assertTrue(statement.sql().contains("null as payload"), "Car 브랜치는 payload를 null로 정렬: " + statement.sql());
        assertTrue(statement.sql().contains("null as doors"), "Truck 브랜치는 doors를 null로 정렬: " + statement.sql());
        assertTrue(statement.sql().contains("from t_car"), statement.sql());
        assertTrue(statement.sql().contains("from t_truck"), statement.sql());
    }

    @Test
    void tablePerClassInsertHasNoDiscriminatorColumn() {
        EntityMetadata<TCar> car = factory.getEntityMetadata(TCar.class);
        TCar entity = new TCar();
        entity.id = 1L;
        entity.name = "ada";
        entity.doors = 4;
        SqlStatement statement = dialect.sqlRenderer().insert(car, entity);
        // TPC는 물리 discriminator 컬럼이 없으므로 insert에 kind가 들어가지 않는다.
        assertEquals("insert into t_car (id, name, doors) values (?, ?, ?)", statement.sql());
        assertEquals(java.util.List.of(1L, "ada", 4), statement.bindings());
    }

    @Test
    void joinedRootTableDdlHasCommonColumnsAndDiscriminator() {
        InheritanceLayout layout = joinedLayout();
        String ddl = dialect.schemaGenerator().createJoinedRootTable(layout, false);
        assertEquals(
                "create table j_vehicle (id bigint primary key, name varchar(255), kind varchar(31) not null)",
                ddl);
    }

    @Test
    void joinedSubtypeTableDdlHasFkPkAndOwnColumns() {
        InheritanceLayout layout = joinedLayout();
        InheritanceLayout.ConcreteSubtype car = layout.subtypes().stream()
                .filter(s -> s.metadata().entityType() == JCar.class).findFirst().orElseThrow();
        String ddl = dialect.schemaGenerator().createJoinedSubtypeTable(layout, car, false);
        // FK PK는 IDENTITY가 아니라 plain bigint primary key여야 한다(값을 루트에서 받는다).
        assertEquals("create table j_car (id bigint not null primary key, doors integer not null)", ddl);
    }

    @Test
    void tablePerClassTableDdlHasAllColumnsNoDiscriminator() {
        EntityMetadata<TCar> car = factory.getEntityMetadata(TCar.class);
        String ddl = dialect.schemaGenerator().createTable(car);
        assertEquals(
                "create table t_car (id bigint primary key, name varchar(255), doors integer not null)",
                ddl);
    }

    @Test
    void updateJoinedSubtypeReturnsNullWhenNoOwnColumns() {
        // 자기 컬럼이 없는 서브타입은 서브타입 UPDATE 단계가 null이어야 한다.
        factory.getEntityMetadata(JPlain.class);
        InheritanceLayout layout = factory.inheritanceLayout(JVehicle.class);
        InheritanceLayout.ConcreteSubtype plain = layout.subtypes().stream()
                .filter(s -> s.metadata().entityType() == JPlain.class).findFirst().orElseThrow();
        EntityMetadata<JPlain> meta = factory.getEntityMetadata(JPlain.class);
        JPlain entity = new JPlain();
        entity.id = 1L;
        assertNull(dialect.sqlRenderer().updateJoinedSubtype(meta, plain.ownTableColumns(), entity));
    }

    // --- fixtures ----------------------------------------------------------

    @Entity
    @Table(name = "j_vehicle")
    @Inheritance(strategy = InheritanceType.JOINED)
    @DiscriminatorColumn(name = "kind", discriminatorType = DiscriminatorType.STRING)
    abstract static class JVehicle {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        Long id;
        String name;
    }

    @Entity
    @Table(name = "j_car")
    @DiscriminatorValue("CAR")
    static class JCar extends JVehicle {
        int doors;
    }

    @Entity
    @Table(name = "j_truck")
    @DiscriminatorValue("TRUCK")
    static class JTruck extends JVehicle {
        double payload;
    }

    @Entity
    @Table(name = "j_plain")
    @DiscriminatorValue("PLAIN")
    static class JPlain extends JVehicle {
    }

    @Entity
    @Table(name = "t_vehicle")
    @Inheritance(strategy = InheritanceType.TABLE_PER_CLASS)
    @DiscriminatorColumn(name = "kind", discriminatorType = DiscriminatorType.STRING)
    abstract static class TVehicle {
        @Id
        Long id;
        String name;
    }

    @Entity
    @Table(name = "t_car")
    @DiscriminatorValue("CAR")
    static class TCar extends TVehicle {
        int doors;
    }

    @Entity
    @Table(name = "t_truck")
    @DiscriminatorValue("TRUCK")
    static class TTruck extends TVehicle {
        double payload;
    }

    private static final class TestDialect implements Dialect {
        private final BindMarkerStrategy bindMarkers = index -> "?";
        private final SqlRenderer renderer = new AbstractSqlRenderer(this) {
        };
        private final SchemaGenerator schemaGenerator = new AbstractSchemaGenerator(this) {
        };

        @Override
        public String name() {
            return "test";
        }

        @Override
        public String quote(String identifier) {
            return identifier;
        }

        @Override
        public BindMarkerStrategy bindMarkers() {
            return bindMarkers;
        }

        @Override
        public SqlRenderer sqlRenderer() {
            return renderer;
        }

        @Override
        public SchemaGenerator schemaGenerator() {
            return schemaGenerator;
        }
    }
}
