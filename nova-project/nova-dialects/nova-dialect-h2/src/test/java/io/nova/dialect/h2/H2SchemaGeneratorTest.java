package io.nova.dialect.h2;

import io.nova.metadata.DefaultNamingStrategy;
import io.nova.metadata.EntityMetadata;
import io.nova.metadata.EntityMetadataFactory;
import io.nova.metadata.TableGeneratorInfo;
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

    @Test
    void rendersCreateTableGeneratorWithVarcharPkAndBigintCounter() {
        TableGeneratorInfo info = new TableGeneratorInfo(
                "id_generators", "gen_name", "gen_value", "account_id", 100, 5);

        assertEquals(
                "create table \"id_generators\" "
                        + "(\"gen_name\" varchar(255) not null primary key, \"gen_value\" bigint not null)",
                dialect.schemaGenerator().createTableGenerator(info)
        );
    }

    @Test
    void seedsTableGeneratorSoFirstAllocatedIdEqualsInitialValue() {
        TableGeneratorInfo info = new TableGeneratorInfo(
                "id_generators", "gen_name", "gen_value", "account_id", 100, 5);

        // 증가-우선 블록 모델: 카운터는 "다음 발급 첫 id"를 보관하므로 seed = initialValue(100).
        // 첫 발급 시 UPDATE로 105가 되고 블록 [100,104]를 역산하므로 첫 id가 정확히 100이다.
        assertEquals(
                "insert into \"id_generators\" (\"gen_name\", \"gen_value\") values ('account_id', 100)",
                dialect.schemaGenerator().seedTableGenerator(info)
        );
    }
}
