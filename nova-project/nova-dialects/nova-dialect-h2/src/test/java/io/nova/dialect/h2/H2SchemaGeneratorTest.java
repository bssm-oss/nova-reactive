package io.nova.dialect.h2;

import io.nova.metadata.DefaultNamingStrategy;
import io.nova.metadata.EntityMetadata;
import io.nova.metadata.EntityMetadataFactory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class H2SchemaGeneratorTest {
    private final H2Dialect dialect = new H2Dialect();
    private final EntityMetadataFactory metadataFactory = new EntityMetadataFactory(new DefaultNamingStrategy());

    @Test
    void rendersCreateTableWithIdentityColumnUsingGeneratedAlwaysAsIdentity() {
        EntityMetadata<H2SampleAccount> metadata = metadataFactory.getEntityMetadata(H2SampleAccount.class);

        assertEquals(
                "create table \"accounts\" (\"id\" bigint generated always as identity primary key, \"email_address\" varchar(255), \"active\" boolean not null)",
                dialect.schemaGenerator().createTable(metadata)
        );
    }

    @Test
    void rendersCreateTableForAssignedIdWithoutIdentitySyntax() {
        EntityMetadata<H2AssignedIdAccount> metadata = metadataFactory.getEntityMetadata(H2AssignedIdAccount.class);

        assertEquals(
                "create table \"assigned_accounts\" (\"id\" bigint primary key, \"email_address\" varchar(255))",
                dialect.schemaGenerator().createTable(metadata)
        );
    }
}
