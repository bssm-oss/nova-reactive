package io.nova.dialect.oracle;

import io.nova.metadata.DefaultNamingStrategy;
import io.nova.metadata.EntityMetadata;
import io.nova.metadata.EntityMetadataFactory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
