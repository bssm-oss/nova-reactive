package io.nova.dialect.postgresql;

import io.nova.metadata.DefaultNamingStrategy;
import io.nova.metadata.EntityMetadata;
import io.nova.metadata.EntityMetadataFactory;
import io.nova.query.Criteria;
import io.nova.query.Pageable;
import io.nova.query.QuerySpec;
import io.nova.query.Sort;
import io.nova.sql.SqlStatement;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PostgresqlDialectTest {
    private final PostgresqlDialect dialect = new PostgresqlDialect();
    private final EntityMetadata<PostgresqlSampleAccount> metadata = new EntityMetadataFactory(new DefaultNamingStrategy())
            .getEntityMetadata(PostgresqlSampleAccount.class);

    @Test
    void rendersPagedSelectUsingPositionalBindMarkers() {
        SqlStatement statement = dialect.sqlRenderer().select(
                metadata,
                QuerySpec.empty()
                        .where(Criteria.eq("email", "a@nova.io"))
                        .orderBy(Sort.by(Sort.Order.desc("id")))
                        .page(Pageable.of(5, 10))
        );

        assertEquals(
                "select \"id\" as \"id\", \"email_address\" as \"email_address\", \"active\" as \"active\" from \"accounts\" where \"email_address\" = $1 order by \"id\" desc limit $2 offset $3",
                statement.sql()
        );
        assertEquals(java.util.List.of("a@nova.io", 5, 10L), statement.bindings());
    }

    @Test
    void rendersIdentityColumnForSchema() {
        assertEquals(
                "create table \"accounts\" (\"id\" bigserial primary key, \"email_address\" varchar(255), \"active\" boolean not null)",
                dialect.schemaGenerator().createTable(metadata)
        );
    }

    @Test
    void rendersInsertWithNumberedBindMarkersAndReturningClauseForIdentityId() {
        SqlStatement statement = dialect.sqlRenderer().insert(
                metadata,
                new PostgresqlSampleAccount("pg@nova.io", true)
        );

        assertEquals(
                "insert into \"accounts\" (\"email_address\", \"active\") values ($1, $2) returning \"id\"",
                statement.sql()
        );
        assertEquals(java.util.List.of("pg@nova.io", true), statement.bindings());
    }

    @Test
    void omitsReturningClauseForAssignedId() {
        EntityMetadata<PostgresqlAssignedIdAccount> assigned = new EntityMetadataFactory(new DefaultNamingStrategy())
                .getEntityMetadata(PostgresqlAssignedIdAccount.class);

        SqlStatement statement = dialect.sqlRenderer().insert(
                assigned,
                new PostgresqlAssignedIdAccount(7L, "assigned@nova.io")
        );

        assertEquals(
                "insert into \"assigned_accounts\" (\"id\", \"email_address\") values ($1, $2)",
                statement.sql()
        );
        assertEquals(java.util.List.of(7L, "assigned@nova.io"), statement.bindings());
    }

    @Test
    void reportsUseOfReturningClauseForGeneratedKeys() {
        org.junit.jupiter.api.Assertions.assertTrue(dialect.usesReturningForGeneratedKeys());
    }

    @Test
    void rendersSequenceNextValueSql() {
        assertEquals(
                "select nextval('account_id_seq')",
                dialect.sequenceNextValueSql("account_id_seq")
        );
    }

    @Test
    void sequenceNextValueSqlRejectsBlankName() {
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> dialect.sequenceNextValueSql(" ")
        );
    }
}
