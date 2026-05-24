package io.nova.dialect.oracle;

import io.nova.metadata.DefaultNamingStrategy;
import io.nova.metadata.EntityMetadata;
import io.nova.metadata.EntityMetadataFactory;
import io.nova.query.Criteria;
import io.nova.query.LockMode;
import io.nova.query.QuerySpec;
import io.nova.sql.Dialect;
import io.nova.sql.SqlStatement;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OracleDialectTest {
    private final OracleDialect dialect = new OracleDialect();
    private final EntityMetadata<OracleSampleAccount> metadata = new EntityMetadataFactory(new DefaultNamingStrategy())
            .getEntityMetadata(OracleSampleAccount.class);

    @Test
    void reportsOracleNameAndQuotesIdentifiersWithDoubleQuotes() {
        assertEquals("oracle", dialect.name());
        assertEquals("\"accounts\"", dialect.quote("accounts"));
    }

    @Test
    void usesQuestionMarkPositionalBindMarkers() {
        assertEquals("?", dialect.bindMarkers().marker(1));
        assertEquals("?", dialect.bindMarkers().marker(7));
    }

    @Test
    void rendersDeleteWithDoubleQuotedIdentifiersAndQuestionMark() {
        SqlStatement statement = dialect.sqlRenderer().deleteById(metadata, 9L);

        assertEquals("delete from \"accounts\" where \"id\" = ?", statement.sql());
        assertEquals(List.of(9L), statement.bindings());
    }

    @Test
    void insertOmitsReturningClauseAndDialectReportsNoReturningKeySupport() {
        SqlStatement statement = dialect.sqlRenderer().insert(
                metadata,
                new OracleSampleAccount(null, "oracle@nova.io", true)
        );

        assertEquals(
                "insert into \"accounts\" (\"email_address\", \"active\") values (?, ?)",
                statement.sql()
        );
        assertEquals(List.of("oracle@nova.io", true), statement.bindings());
        assertFalse(dialect.usesReturningForGeneratedKeys());
    }

    @Test
    void rendersSequenceNextValueSqlWithFromDualAndStableAlias() {
        assertEquals(
                "select account_id_seq.nextval as " + Dialect.SEQUENCE_VALUE_COLUMN + " from dual",
                dialect.sequenceNextValueSql("account_id_seq")
        );
    }

    @Test
    void sequenceNextValueSqlRejectsBlankName() {
        assertThrows(IllegalArgumentException.class, () -> dialect.sequenceNextValueSql(" "));
        assertThrows(IllegalArgumentException.class, () -> dialect.sequenceNextValueSql(null));
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
    void lockClauseRejectsForShareBecauseOracleHasNoRowLevelShareLock() {
        UnsupportedOperationException exception = assertThrows(
                UnsupportedOperationException.class,
                () -> dialect.lockClause(LockMode.FOR_SHARE)
        );

        assertTrue(exception.getMessage().contains("FOR SHARE"));
    }

    @Test
    void selectAppendsForUpdateClauseAfterWhereForOracleQuotedSelect() {
        SqlStatement statement = dialect.sqlRenderer().select(
                metadata,
                QuerySpec.empty().where(Criteria.eq("email", "a@nova.io")).forUpdate()
        );

        assertEquals(
                "select \"id\" as \"id\", \"email_address\" as \"email_address\", \"active\" as \"active\" "
                        + "from \"accounts\" where \"email_address\" = ? for update",
                statement.sql()
        );
    }
}
