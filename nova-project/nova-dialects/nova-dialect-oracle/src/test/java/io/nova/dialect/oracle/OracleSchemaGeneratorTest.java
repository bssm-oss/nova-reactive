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
