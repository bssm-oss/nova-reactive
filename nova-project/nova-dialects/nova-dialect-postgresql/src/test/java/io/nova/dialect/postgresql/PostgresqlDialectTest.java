package io.nova.dialect.postgresql;

import io.nova.metadata.DefaultNamingStrategy;
import io.nova.metadata.EntityMetadata;
import io.nova.metadata.EntityMetadataFactory;
import io.nova.query.Criteria;
import io.nova.query.LockMode;
import io.nova.query.Pageable;
import io.nova.query.QuerySpec;
import io.nova.query.Sort;
import io.nova.sql.SqlStatement;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void jsonColumnTypeIsJsonb() {
        assertEquals("jsonb", dialect.jsonColumnType());
    }

    @Test
    void rendersJsonColumnAsJsonbInSchema() {
        EntityMetadata<PostgresqlJsonAccount> jsonMetadata = new EntityMetadataFactory(new DefaultNamingStrategy())
                .getEntityMetadata(PostgresqlJsonAccount.class);

        assertEquals(
                "create table \"json_accounts\" (\"id\" bigint primary key, \"payload\" jsonb)",
                dialect.schemaGenerator().createTable(jsonMetadata)
        );
    }

    @Test
    void rendersSequenceNextValueSqlWithStableAlias() {
        assertEquals(
                "select nextval('account_id_seq') as " + io.nova.sql.Dialect.SEQUENCE_VALUE_COLUMN,
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

    @Test
    void rendersTableGeneratorIncrementSqlWithQuotedIdentifiers() {
        assertEquals(
                "update \"id_generators\" set \"gen_value\" = \"gen_value\" + 5"
                        + " where \"gen_name\" = 'account_id'",
                dialect.tableGeneratorIncrementSql("id_generators", "gen_value", "gen_name", "account_id", 5)
        );
    }

    @Test
    void rendersTableGeneratorSelectSqlWithStableAlias() {
        assertEquals(
                "select \"gen_value\" as " + io.nova.sql.Dialect.TABLE_GENERATOR_VALUE_COLUMN
                        + " from \"id_generators\" where \"gen_name\" = 'account_id'",
                dialect.tableGeneratorSelectSql("id_generators", "gen_value", "gen_name", "account_id")
        );
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
    void rendersIlikeUsingNativePostgresOperator() {
        SqlStatement statement = dialect.sqlRenderer().select(
                metadata,
                QuerySpec.empty().where(Criteria.ilike("email", "%NOVA%"))
        );

        assertEquals(
                "select \"id\" as \"id\", \"email_address\" as \"email_address\", \"active\" as \"active\" from \"accounts\" where \"email_address\" ilike $1",
                statement.sql()
        );
        assertEquals(java.util.List.of("%NOVA%"), statement.bindings());
    }

    @Test
    void rendersNotIlikeUsingNativePostgresOperator() {
        SqlStatement statement = dialect.sqlRenderer().select(
                metadata,
                QuerySpec.empty().where(Criteria.notIlike("email", "noreply%"))
        );

        assertEquals(
                "select \"id\" as \"id\", \"email_address\" as \"email_address\", \"active\" as \"active\" from \"accounts\" where \"email_address\" not ilike $1",
                statement.sql()
        );
        assertEquals(java.util.List.of("noreply%"), statement.bindings());
    }

    @Test
    void rendersContainsIgnoreCaseAsNativeIlike() {
        SqlStatement statement = dialect.sqlRenderer().select(
                metadata,
                QuerySpec.empty().where(Criteria.containsIgnoreCase("email", "Nova"))
        );

        assertEquals(
                "select \"id\" as \"id\", \"email_address\" as \"email_address\", \"active\" as \"active\" from \"accounts\" where \"email_address\" ilike $1",
                statement.sql()
        );
        assertEquals(java.util.List.of("%Nova%"), statement.bindings());
    }

    @Test
    void rendersStartsWithUsingPlainLike() {
        SqlStatement statement = dialect.sqlRenderer().select(
                metadata,
                QuerySpec.empty().where(Criteria.startsWith("email", "ada"))
        );

        assertEquals(
                "select \"id\" as \"id\", \"email_address\" as \"email_address\", \"active\" as \"active\" from \"accounts\" where \"email_address\" like $1",
                statement.sql()
        );
        assertEquals(java.util.List.of("ada%"), statement.bindings());
    }

    @Test
    void selectAppendsForUpdateClauseAfterPagingForPositionalDialect() {
        SqlStatement statement = dialect.sqlRenderer().select(
                metadata,
                QuerySpec.empty()
                        .where(Criteria.eq("email", "a@nova.io"))
                        .page(Pageable.of(5, 10))
                        .forUpdate()
        );

        assertEquals(
                "select \"id\" as \"id\", \"email_address\" as \"email_address\", \"active\" as \"active\" "
                        + "from \"accounts\" where \"email_address\" = $1 limit $2 offset $3 for update",
                statement.sql()
        );
    }

    @Test
    void addForeignKeyQuotesIdentifiersWithDoubleQuotes() {
        String ddl = dialect.schemaGenerator().addForeignKey(new io.nova.metadata.ForeignKeyDefinition(
                "fk_child", "fk_child_parent",
                java.util.List.of("parent_id"), "fk_parent", java.util.List.of("id")));

        assertEquals(
                "alter table \"fk_child\" add constraint \"fk_child_parent\""
                        + " foreign key (\"parent_id\") references \"fk_parent\" (\"id\")",
                ddl);
    }

    @Test
    void rendersElementCollectionValueColumnTypesByStorageType() {
        EntityMetadataFactory factory = new EntityMetadataFactory(new DefaultNamingStrategy());
        EntityMetadata<EcHolder> holder = factory.getEntityMetadata(EcHolder.class);

        String stringColorsDdl = dialect.schemaGenerator().createCollectionTable(
                holder.findProperty("stringColors").orElseThrow()
                        .elementCollectionInfo().toCollectionTableDefinition(Long.class));
        assertTrue(stringColorsDdl.contains("\"string_colors\" varchar(255)"), stringColorsDdl);

        String ordinalColorsDdl = dialect.schemaGenerator().createCollectionTable(
                holder.findProperty("ordinalColors").orElseThrow()
                        .elementCollectionInfo().toCollectionTableDefinition(Long.class));
        assertTrue(ordinalColorsDdl.contains("\"ordinal_colors\" integer"), ordinalColorsDdl);

        String refsDdl = dialect.schemaGenerator().createCollectionTable(
                holder.findProperty("refs").orElseThrow()
                        .elementCollectionInfo().toCollectionTableDefinition(Long.class));
        assertTrue(refsDdl.contains("\"refs\" varchar(255)"), refsDdl);
    }

    enum Hue { RED, GREEN, BLUE }

    @jakarta.persistence.Entity
    @jakarta.persistence.Table(name = "ec_holder")
    static class EcHolder {
        @jakarta.persistence.Id
        Long id;

        @jakarta.persistence.ElementCollection
        @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
        java.util.Set<Hue> stringColors;

        @jakarta.persistence.ElementCollection
        @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.ORDINAL)
        java.util.Set<Hue> ordinalColors;

        @jakarta.persistence.ElementCollection
        java.util.Set<java.util.UUID> refs;
    }
}
