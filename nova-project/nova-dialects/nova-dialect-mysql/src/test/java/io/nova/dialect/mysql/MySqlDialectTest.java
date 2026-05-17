package io.nova.dialect.mysql;

import io.nova.metadata.DefaultNamingStrategy;
import io.nova.metadata.EntityMetadata;
import io.nova.metadata.EntityMetadataFactory;
import io.nova.query.Criteria;
import io.nova.query.QuerySpec;
import io.nova.sql.SqlStatement;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MySqlDialectTest {
    private final MySqlDialect dialect = new MySqlDialect();
    private final EntityMetadata<MySqlSampleAccount> metadata = new EntityMetadataFactory(new DefaultNamingStrategy())
            .getEntityMetadata(MySqlSampleAccount.class);

    @Test
    void rendersDeleteAndSchemaWithMysqlQuoting() {
        SqlStatement statement = dialect.sqlRenderer().deleteById(metadata, 9L);

        assertEquals("delete from `accounts` where `id` = ?", statement.sql());
        assertEquals(java.util.List.of(9L), statement.bindings());
        assertEquals(
                "create table `accounts` (`id` bigint primary key auto_increment, `email_address` varchar(255), `active` boolean not null)",
                dialect.schemaGenerator().createTable(metadata)
        );
    }

    @Test
    void rendersExistsQuery() {
        SqlStatement statement = dialect.sqlRenderer().exists(
                metadata,
                QuerySpec.empty().where(Criteria.isNotNull("email"))
        );

        assertEquals("select 1 from `accounts` where `email_address` is not null limit 1", statement.sql());
    }

    @Test
    void rendersUpdateWithQuestionMarkMarkers() {
        SqlStatement statement = dialect.sqlRenderer().update(
                metadata,
                new MySqlSampleAccount(4L, "mysql@nova.io", false)
        );

        assertEquals(
                "update `accounts` set `email_address` = ?, `active` = ? where `id` = ?",
                statement.sql()
        );
        assertEquals(java.util.List.of("mysql@nova.io", false, 4L), statement.bindings());
    }
}
