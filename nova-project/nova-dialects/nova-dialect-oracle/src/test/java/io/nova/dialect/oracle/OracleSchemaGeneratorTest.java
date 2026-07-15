package io.nova.dialect.oracle;

import io.nova.metadata.DefaultNamingStrategy;
import io.nova.metadata.EntityMetadata;
import io.nova.metadata.EntityMetadataFactory;
import io.nova.metadata.InheritanceLayout;
import io.nova.metadata.PersistentProperty;
import io.nova.metadata.SecondaryTableInfo;
import io.nova.metadata.TableGeneratorInfo;
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
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OracleSchemaGeneratorTest {
    private final OracleDialect dialect = new OracleDialect();
    private final EntityMetadataFactory metadataFactory = new EntityMetadataFactory(new DefaultNamingStrategy());

    @Test
    void rendersCreateTableWithOracleIdentityColumnAndNativeTypes() {
        EntityMetadata<OracleSampleAccount> metadata = metadataFactory.getEntityMetadata(OracleSampleAccount.class);

        assertEquals(
                "create table \"accounts\" (\"id\" number(19) generated always as identity primary key, "
                        + "\"email_address\" varchar2(255), \"active\" number(1) not null)",
                dialect.schemaGenerator().createTable(metadata)
        );
    }

    @Test
    void rendersCreateTableForAssignedIdWithoutIdentitySyntax() {
        EntityMetadata<OracleAssignedIdAccount> metadata = metadataFactory.getEntityMetadata(OracleAssignedIdAccount.class);

        assertEquals(
                "create table \"assigned_accounts\" (\"id\" number(19) primary key, \"email_address\" varchar2(255))",
                dialect.schemaGenerator().createTable(metadata)
        );
    }

    @Test
    void mapsJsonColumnToClobNotJavaTypeVarchar() {
        // @Json payload의 javaType은 String이지만, sqlType override가 json 가드를 가장 먼저 두므로
        // varchar2(255)가 아니라 dialect의 jsonColumnType()으로 매핑돼야 한다. Oracle은 12c~19c
        // 호환을 위해 jsonColumnType()을 "clob"로 override 한다.
        EntityMetadata<OracleJsonAccount> metadata = metadataFactory.getEntityMetadata(OracleJsonAccount.class);

        assertEquals(
                "create table \"json_accounts\" (\"id\" number(19) primary key, \"payload\" clob)",
                dialect.schemaGenerator().createTable(metadata)
        );
    }

    @Test
    void boundsUnnamedForeignKeyNameToOracle30CharLimit() {
        // Oracle 12.2 미만의 30바이트 식별자 한계 — 긴 fk_<table>_<column> 자동 이름이 30자 내로 bound되고,
        // 멱등 발행의 존재 체크 키(foreignKeyName)와 실제 DDL의 제약 이름이 정확히 일치해야 한다.
        io.nova.metadata.ForeignKeyDefinition def = new io.nova.metadata.ForeignKeyDefinition(
                "warehouse_inventory_reconciliation", "",
                java.util.List.of("distribution_center_id"), "ref", java.util.List.of("id"));

        String name = dialect.schemaGenerator().foreignKeyName(def);
        assertTrue(name.length() <= 30, "bounded to Oracle 30-char limit, got " + name.length() + ": " + name);
        assertEquals(name, dialect.schemaGenerator().foreignKeyName(def));
        String ddl = dialect.schemaGenerator().addForeignKey(def);
        assertTrue(ddl.contains("add constraint \"" + name + "\" foreign key"),
                "addForeignKey must embed the exact bounded name, got " + ddl);
    }

    @Test
    void mapsColumnLengthPrecisionAndScaleToOracleNativeTypes() {
        EntityMetadata<OracleColumnTypedAccount> metadata =
                metadataFactory.getEntityMetadata(OracleColumnTypedAccount.class);

        assertEquals(
                "create table \"column_typed\" ("
                        + "\"id\" number(19) primary key, "
                        + "\"short_name\" varchar2(64), "
                        + "\"description\" varchar2(255), "
                        + "\"price\" number(12, 2), "
                        + "\"default_decimal\" number(19, 2))",
                dialect.schemaGenerator().createTable(metadata)
        );
    }

    @Test
    void mapsScalarAndEnumTypesToOracleNativeTypes() {
        EntityMetadata<OracleTypeAccount> metadata = metadataFactory.getEntityMetadata(OracleTypeAccount.class);

        assertEquals(
                "create table \"type_accounts\" ("
                        + "\"id\" number(19) primary key, "
                        + "\"balance\" number(10), "
                        + "\"ratio\" binary_double, "
                        + "\"enabled\" number(1) not null, "
                        + "\"name\" varchar2(255), "
                        + "\"tier\" varchar2(255), "
                        + "\"ordinal_tier\" number(10))",
                dialect.schemaGenerator().createTable(metadata)
        );
    }

    // --- @TableGenerator counter table --------------------------------------

    @Test
    void rendersTableGeneratorWithOracleNumberCounterNotAnsiBigint() {
        // base emits `bigint` counter → invalid on Oracle (ORA-00902, same class as the historical
        // foreignKeyColumnType/fkColumnType/elementColumnType bigint leak). Must be number(19)/varchar2.
        TableGeneratorInfo info = new TableGeneratorInfo(
                "id_generators", "gen_name", "gen_value", "account_id", 100, 5);

        String ddl = dialect.schemaGenerator().createTableGenerator(info);
        assertEquals(
                "create table \"id_generators\" "
                        + "(\"gen_name\" varchar2(255) not null primary key, \"gen_value\" number(19) not null)",
                ddl);
        assertFalse(ddl.contains("bigint"), "no ANSI bigint token may leak to Oracle: " + ddl);
        assertFalse(ddl.contains(" varchar("), "no ANSI varchar() token may leak to Oracle: " + ddl);
    }

    @Test
    void wrapsIdempotentGeneratorDdlInPlSqlBlockNotAnsiIfExists() {
        // Oracle has no CREATE/DROP ... IF [NOT] EXISTS; the base default would emit exactly that
        // (ORA-00922). Both idempotent generator surfaces must route through the PL/SQL block instead.
        TableGeneratorInfo info = new TableGeneratorInfo(
                "id_generators", "gen_name", "gen_value", "account_id", 100, 5);

        String create = dialect.schemaGenerator().createTableGeneratorIfNotExists(info);
        assertTrue(create.startsWith("begin execute immediate '") && create.endsWith("end;"), create);
        assertTrue(create.contains("sqlcode != -955"), create);
        assertFalse(create.contains("if not exists"), create);
        assertTrue(create.contains("number(19)") && !create.contains("bigint"), create);

        String drop = dialect.schemaGenerator().dropTableGeneratorIfExists("id_generators");
        assertEquals(
                "begin execute immediate 'drop table \"id_generators\" purge'; "
                        + "exception when others then if sqlcode != -942 then raise; end if; end;",
                drop);
        assertFalse(drop.contains("if exists"), drop);
    }

    // --- ddl-auto=UPDATE column additions (ORA-00905 on ANSI `ADD COLUMN`) ---

    @Test
    void rendersAlterTableAddColumnWithOracleAddParenSyntaxNotAnsiAddColumn() {
        EntityMetadata<OracleSampleAccount> metadata = metadataFactory.getEntityMetadata(OracleSampleAccount.class);
        PersistentProperty emailColumn = metadata.findProperty("email").orElseThrow();

        String ddl = dialect.schemaGenerator().alterTableAddColumn(metadata, emailColumn);
        assertEquals("alter table \"accounts\" add (\"email_address\" varchar2(255))", ddl);
        assertFalse(ddl.contains("add column"), "Oracle rejects the ANSI ADD COLUMN keyword (ORA-00905): " + ddl);
    }

    @Test
    void rendersOneToManyOrderColumnWithOracleAddParenSyntax() {
        EntityMetadata<OracleSampleAccount> metadata = metadataFactory.getEntityMetadata(OracleSampleAccount.class);

        String ddl = dialect.schemaGenerator().addOneToManyOrderColumn(metadata, "list_order");
        assertEquals("alter table \"accounts\" add (\"list_order\" integer)", ddl);
        assertFalse(ddl.contains("add column"), "Oracle rejects the ANSI ADD COLUMN keyword (ORA-00905): " + ddl);
    }

    // --- @SecondaryTable idempotent DDL -------------------------------------

    @Test
    void wrapsIdempotentSecondaryTableDdlInPlSqlBlock() {
        EntityMetadata<OracleSampleAccount> metadata = metadataFactory.getEntityMetadata(OracleSampleAccount.class);
        SecondaryTableInfo secondary = new SecondaryTableInfo("account_details", "account_id", "id");

        String create = dialect.schemaGenerator().createSecondaryTableIfNotExists(metadata, secondary);
        assertTrue(create.startsWith("begin execute immediate '") && create.endsWith("end;"), create);
        assertFalse(create.contains("if not exists"), create);

        String drop = dialect.schemaGenerator().dropSecondaryTableIfExists(secondary);
        assertEquals(
                "begin execute immediate 'drop table \"account_details\" purge'; "
                        + "exception when others then if sqlcode != -942 then raise; end if; end;",
                drop);
        assertFalse(drop.contains("if exists"), drop);
    }

    // --- JOINED inheritance idempotent CREATE -------------------------------

    @Test
    void wrapsIdempotentJoinedInheritanceDdlInPlSqlBlock() {
        metadataFactory.getEntityMetadata(JoinedCar.class);
        InheritanceLayout layout = metadataFactory.inheritanceLayout(JoinedVehicle.class);

        String root = dialect.schemaGenerator().createJoinedRootTable(layout, true);
        assertTrue(root.startsWith("begin execute immediate '") && root.endsWith("end;"), root);
        assertFalse(root.contains("if not exists"), root);
        // Non-idempotent path stays plain DDL (no PL/SQL wrapping).
        assertFalse(dialect.schemaGenerator().createJoinedRootTable(layout, false).startsWith("begin"), "plain");

        InheritanceLayout.ConcreteSubtype car = layout.subtypes().stream()
                .filter(s -> s.metadata().entityType() == JoinedCar.class).findFirst().orElseThrow();
        String subtype = dialect.schemaGenerator().createJoinedSubtypeTable(layout, car, true);
        assertTrue(subtype.startsWith("begin execute immediate '") && subtype.endsWith("end;"), subtype);
        assertFalse(subtype.contains("if not exists"), subtype);
    }

    // --- fixtures -----------------------------------------------------------

    @Entity
    @Table(name = "joined_vehicle")
    @Inheritance(strategy = InheritanceType.JOINED)
    @DiscriminatorColumn(name = "kind", discriminatorType = DiscriminatorType.STRING)
    abstract static class JoinedVehicle {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        Long id;
        String name;
    }

    @Entity
    @Table(name = "joined_car")
    @DiscriminatorValue("CAR")
    static class JoinedCar extends JoinedVehicle {
        int doors;
    }
}
