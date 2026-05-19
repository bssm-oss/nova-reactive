package io.nova.dialect.mysql;

import io.nova.metadata.DefaultNamingStrategy;
import io.nova.metadata.EntityMetadata;
import io.nova.metadata.EntityMetadataFactory;
import io.nova.query.Criteria;
import io.nova.query.LockMode;
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

    @Test
    void sequenceNextValueSqlIsUnsupported() {
        UnsupportedOperationException exception = org.junit.jupiter.api.Assertions.assertThrows(
                UnsupportedOperationException.class,
                () -> dialect.sequenceNextValueSql("seq")
        );

        org.junit.jupiter.api.Assertions.assertTrue(exception.getMessage().contains("mysql"));
    }

    @Test
    void insertOmitsReturningClauseAndDialectReportsNoReturningKeySupport() {
        SqlStatement statement = dialect.sqlRenderer().insert(
                metadata,
                new MySqlSampleAccount(null, "mysql@nova.io", true)
        );

        assertEquals(
                "insert into `accounts` (`email_address`, `active`) values (?, ?)",
                statement.sql()
        );
        assertEquals(java.util.List.of("mysql@nova.io", true), statement.bindings());
        org.junit.jupiter.api.Assertions.assertFalse(dialect.usesReturningForGeneratedKeys());
    }

    @Test
    void lockClauseReturnsEmptyForNone() {
        assertEquals("", dialect.lockClause(LockMode.NONE));
    }

    @Test
    void lockClauseReturnsForUpdate() {
        assertEquals(" for update", dialect.lockClause(LockMode.FOR_UPDATE));
    }

    @Test
    void lockClauseReturnsForShare() {
        assertEquals(" for share", dialect.lockClause(LockMode.FOR_SHARE));
    }

    @Test
    void selectAppendsForUpdateClauseForMysqlQuotedSelect() {
        SqlStatement statement = dialect.sqlRenderer().select(
                metadata,
                QuerySpec.empty().where(Criteria.eq("email", "a@nova.io")).forUpdate()
        );

        assertEquals(
                "select `id` as `id`, `email_address` as `email_address`, `active` as `active` "
                        + "from `accounts` where `email_address` = ? for update",
                statement.sql()
        );
    }
}
