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
    void failsFastOnTemporalTimeSinceOracleHasNoTimeType() {
        // Oracle엔 TIME-only 타입이 없어 ANSI `time` 토큰은 ORA-00902로 깨진다 → 조용히 잘못된 DDL 대신 fail-fast.
        assertThrows(UnsupportedOperationException.class, dialect::timeColumnType);
        // DATE/TIMESTAMP는 Oracle 토큰이 유효하므로 그대로 동작한다.
        assertEquals("date", dialect.dateColumnType());
        assertEquals("timestamp", dialect.timestampColumnType());
    }

    @Test
    void createTableFailsFastWhenAnyColumnIsTemporalTime() {
        // 토큰 단위를 넘어, @Temporal(TIME) 컬럼이 섞인 엔티티의 createTable DDL 생성이 schema generator를 통해
        // 실제로 fail-fast 하는지 검증한다(조용히 잘못된 DDL을 만들지 않음, 라이브 DB 불요).
        EntityMetadata<OracleTemporalTime> metadata =
                new EntityMetadataFactory(new DefaultNamingStrategy()).getEntityMetadata(OracleTemporalTime.class);

        UnsupportedOperationException exception = assertThrows(
                UnsupportedOperationException.class,
                () -> dialect.schemaGenerator().createTable(metadata));
        assertTrue(exception.getMessage().contains("TIME"),
                "message should explain the missing TIME type: " + exception.getMessage());
    }

    @Test
    void createTableEmitsOracleDateAndTimestampTokensForTemporalColumns() {
        // @Temporal(DATE)/@Temporal(TIMESTAMP)는 Oracle 토큰 date/timestamp가 유효하므로 DDL이 정상 생성된다.
        EntityMetadata<OracleTemporalDateTimestamp> metadata =
                new EntityMetadataFactory(new DefaultNamingStrategy()).getEntityMetadata(OracleTemporalDateTimestamp.class);

        assertEquals(
                "create table \"temporal_events\" (\"id\" number(19) generated always as identity primary key,"
                        + " \"event_date\" date, \"event_timestamp\" timestamp)",
                dialect.schemaGenerator().createTable(metadata)
        );
    }

    @jakarta.persistence.Entity
    @jakarta.persistence.Table(name = "temporal_events")
    static class OracleTemporalTime {
        @jakarta.persistence.Id
        @jakarta.persistence.GeneratedValue(strategy = jakarta.persistence.GenerationType.IDENTITY)
        private Long id;

        @jakarta.persistence.Temporal(jakarta.persistence.TemporalType.TIME)
        @jakarta.persistence.Column(name = "event_time")
        private java.util.Date eventTime;

        OracleTemporalTime() {
        }
    }

    @jakarta.persistence.Entity
    @jakarta.persistence.Table(name = "temporal_events")
    static class OracleTemporalDateTimestamp {
        @jakarta.persistence.Id
        @jakarta.persistence.GeneratedValue(strategy = jakarta.persistence.GenerationType.IDENTITY)
        private Long id;

        @jakarta.persistence.Temporal(jakarta.persistence.TemporalType.DATE)
        @jakarta.persistence.Column(name = "event_date")
        private java.util.Date eventDate;

        @jakarta.persistence.Temporal(jakarta.persistence.TemporalType.TIMESTAMP)
        @jakarta.persistence.Column(name = "event_timestamp")
        private java.util.Date eventTimestamp;

        OracleTemporalDateTimestamp() {
        }
    }

    @Test
    void listsForeignKeyNamesFromUserConstraints() {
        // Oracle엔 information_schema가 없으므로 user_constraints의 referential 제약을 읽는다.
        String sql = dialect.listForeignKeyNamesSql();
        assertTrue(sql.contains("user_constraints"));
        assertTrue(sql.contains(Dialect.FOREIGN_KEY_NAME_COLUMN));
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
