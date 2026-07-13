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
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void mapsTemporalTimestampToDatetimeButKeepsDateAndTime() {
        // @Temporal(TIMESTAMP)은 MySQL TIMESTAMP의 범위/TZ/ON UPDATE 부작용을 피하려고 datetime으로 매핑한다.
        assertEquals("datetime", dialect.timestampColumnType());
        // DATE/TIME 토큰은 MySQL에 존재하므로 ANSI 기본값 그대로 유효하다.
        assertEquals("date", dialect.dateColumnType());
        assertEquals("time", dialect.timeColumnType());
    }

    @Test
    void createTableEmitsDatetimeForTemporalTimestampColumnEndToEnd() {
        // 토큰 단위 검증을 넘어, schema generator의 createTable DDL이 실제로 @Temporal(TIMESTAMP) 컬럼을
        // MySQL `datetime`으로 emit하는지(그리고 DATE/TIME은 date/time 유지) 문자열 단위로 확인한다(라이브 DB 불요).
        EntityMetadata<MySqlTemporalEvent> temporalMetadata =
                new EntityMetadataFactory(new DefaultNamingStrategy()).getEntityMetadata(MySqlTemporalEvent.class);

        assertEquals(
                "create table `temporal_events` (`id` bigint primary key auto_increment,"
                        + " `event_date` date, `event_time` time, `event_timestamp` datetime)",
                dialect.schemaGenerator().createTable(temporalMetadata)
        );
    }

    @jakarta.persistence.Entity
    @jakarta.persistence.Table(name = "temporal_events")
    static class MySqlTemporalEvent {
        @jakarta.persistence.Id
        @jakarta.persistence.GeneratedValue(strategy = jakarta.persistence.GenerationType.IDENTITY)
        private Long id;

        @jakarta.persistence.Temporal(jakarta.persistence.TemporalType.DATE)
        @jakarta.persistence.Column(name = "event_date")
        private java.util.Date eventDate;

        @jakarta.persistence.Temporal(jakarta.persistence.TemporalType.TIME)
        @jakarta.persistence.Column(name = "event_time")
        private java.util.Date eventTime;

        @jakarta.persistence.Temporal(jakarta.persistence.TemporalType.TIMESTAMP)
        @jakarta.persistence.Column(name = "event_timestamp")
        private java.util.Date eventTimestamp;

        MySqlTemporalEvent() {
        }
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
    void rendersTableGeneratorIncrementSqlWithBacktickIdentifiers() {
        assertEquals(
                "update `id_generators` set `gen_value` = `gen_value` + 5"
                        + " where `gen_name` = 'account_id'",
                dialect.tableGeneratorIncrementSql("id_generators", "gen_value", "gen_name", "account_id", 5)
        );
    }

    @Test
    void rendersTableGeneratorSelectSqlWithStableAlias() {
        assertEquals(
                "select `gen_value` as " + io.nova.sql.Dialect.TABLE_GENERATOR_VALUE_COLUMN
                        + " from `id_generators` where `gen_name` = 'account_id'",
                dialect.tableGeneratorSelectSql("id_generators", "gen_value", "gen_name", "account_id")
        );
    }

    @Test
    void rendersIlikeUsingLowerBasedFallback() {
        SqlStatement statement = dialect.sqlRenderer().select(
                metadata,
                QuerySpec.empty().where(Criteria.ilike("email", "%NOVA%"))
        );

        assertEquals(
                "select `id` as `id`, `email_address` as `email_address`, `active` as `active` "
                        + "from `accounts` where lower(`email_address`) like lower(?)",
                statement.sql()
        );
        assertEquals(java.util.List.of("%NOVA%"), statement.bindings());
    }

    @Test
    void rendersNotIlikeUsingLowerBasedFallback() {
        SqlStatement statement = dialect.sqlRenderer().select(
                metadata,
                QuerySpec.empty().where(Criteria.notIlike("email", "noreply%"))
        );

        assertEquals(
                "select `id` as `id`, `email_address` as `email_address`, `active` as `active` "
                        + "from `accounts` where lower(`email_address`) not like lower(?)",
                statement.sql()
        );
        assertEquals(java.util.List.of("noreply%"), statement.bindings());
    }

    @Test
    void rendersStartsWithIgnoreCaseUsingLowerBasedFallback() {
        SqlStatement statement = dialect.sqlRenderer().select(
                metadata,
                QuerySpec.empty().where(Criteria.startsWithIgnoreCase("email", "Ada"))
        );

        assertEquals(
                "select `id` as `id`, `email_address` as `email_address`, `active` as `active` "
                        + "from `accounts` where lower(`email_address`) like lower(?)",
                statement.sql()
        );
        assertEquals(java.util.List.of("Ada%"), statement.bindings());
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

    @Test
    void addForeignKeyQuotesIdentifiersWithBackticks() {
        String ddl = dialect.schemaGenerator().addForeignKey(new io.nova.metadata.ForeignKeyDefinition(
                "fk_child", "fk_child_parent",
                java.util.List.of("parent_id"), "fk_parent", java.util.List.of("id")));

        assertEquals(
                "alter table `fk_child` add constraint `fk_child_parent`"
                        + " foreign key (`parent_id`) references `fk_parent` (`id`)",
                ddl);
    }

    @Test
    void rendersElementCollectionValueColumnTypesByStorageType() {
        EntityMetadataFactory factory = new EntityMetadataFactory(new DefaultNamingStrategy());
        EntityMetadata<EcHolder> holder = factory.getEntityMetadata(EcHolder.class);

        String stringColorsDdl = dialect.schemaGenerator().createCollectionTable(
                holder.findProperty("stringColors").orElseThrow()
                        .elementCollectionInfo().toCollectionTableDefinition(Long.class));
        assertTrue(stringColorsDdl.contains("`string_colors` varchar(255)"), stringColorsDdl);

        String ordinalColorsDdl = dialect.schemaGenerator().createCollectionTable(
                holder.findProperty("ordinalColors").orElseThrow()
                        .elementCollectionInfo().toCollectionTableDefinition(Long.class));
        assertTrue(ordinalColorsDdl.contains("`ordinal_colors` integer"), ordinalColorsDdl);

        String refsDdl = dialect.schemaGenerator().createCollectionTable(
                holder.findProperty("refs").orElseThrow()
                        .elementCollectionInfo().toCollectionTableDefinition(Long.class));
        assertTrue(refsDdl.contains("`refs` varchar(255)"), refsDdl);
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
