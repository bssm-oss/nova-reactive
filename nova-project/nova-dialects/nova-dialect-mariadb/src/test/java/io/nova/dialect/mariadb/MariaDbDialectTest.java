package io.nova.dialect.mariadb;

import io.nova.metadata.DefaultNamingStrategy;
import io.nova.metadata.EntityMetadata;
import io.nova.metadata.EntityMetadataFactory;
import io.nova.query.Criteria;
import io.nova.query.LockMode;
import io.nova.query.QuerySpec;
import io.nova.sql.SqlStatement;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MariaDbDialectTest {
    private final MariaDbDialect dialect = new MariaDbDialect();
    private final EntityMetadata<MariaDbSampleAccount> metadata = new EntityMetadataFactory(new DefaultNamingStrategy())
            .getEntityMetadata(MariaDbSampleAccount.class);

    @Test
    void reportsMariaDbName() {
        assertEquals("mariadb", dialect.name());
    }

    @Test
    void mapsTemporalTimestampToDatetimeButKeepsDateAndTime() {
        assertEquals("datetime", dialect.timestampColumnType());
        assertEquals("date", dialect.dateColumnType());
        assertEquals("time", dialect.timeColumnType());
    }

    @Test
    void createTableEmitsDatetimeForTemporalTimestampColumnEndToEnd() {
        // 토큰 단위 검증을 넘어, createTable DDL이 실제로 @Temporal(TIMESTAMP) 컬럼을 MariaDB `datetime`으로
        // emit하는지(DATE/TIME은 date/time 유지) 문자열 단위로 확인한다(라이브 DB 불요).
        EntityMetadata<MariaDbTemporalEvent> temporalMetadata =
                new EntityMetadataFactory(new DefaultNamingStrategy()).getEntityMetadata(MariaDbTemporalEvent.class);

        assertEquals(
                "create table `temporal_events` (`id` bigint primary key auto_increment,"
                        + " `event_date` date, `event_time` time, `event_timestamp` datetime)",
                dialect.schemaGenerator().createTable(temporalMetadata)
        );
    }

    @jakarta.persistence.Entity
    @jakarta.persistence.Table(name = "temporal_events")
    static class MariaDbTemporalEvent {
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

        MariaDbTemporalEvent() {
        }
    }

    @Test
    void quotesIdentifiersWithBackticks() {
        assertEquals("`accounts`", dialect.quote("accounts"));
    }

    @Test
    void bindMarkersAreQuestionMarksRegardlessOfIndex() {
        assertEquals("?", dialect.bindMarkers().marker(0));
        assertEquals("?", dialect.bindMarkers().marker(5));
    }

    @Test
    void rendersDeleteAndSchemaWithMariaDbQuoting() {
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
                new MariaDbSampleAccount(4L, "mariadb@nova.io", false)
        );

        assertEquals(
                "update `accounts` set `email_address` = ?, `active` = ? where `id` = ?",
                statement.sql()
        );
        assertEquals(java.util.List.of("mariadb@nova.io", false, 4L), statement.bindings());
    }

    @Test
    void sequenceNextValueSqlRendersNextvalWithBacktickQuoting() {
        // MariaDB(10.3+)는 MySQL과 달리 네이티브 SEQUENCE를 지원한다 — NEXTVAL() 인자는 문자열 리터럴이 아니라
        // 시퀀스 식별자이므로 backtick quoting을 쓴다(PostgreSQL의 nextval('name') 문자열 인자와 다른 문법).
        assertEquals(
                "select nextval(`account_id_seq`) as " + io.nova.sql.Dialect.SEQUENCE_VALUE_COLUMN,
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
    void insertOmitsReturningClauseAndDialectReportsNoReturningKeySupport() {
        SqlStatement statement = dialect.sqlRenderer().insert(
                metadata,
                new MariaDbSampleAccount(null, "mariadb@nova.io", true)
        );

        assertEquals(
                "insert into `accounts` (`email_address`, `active`) values (?, ?)",
                statement.sql()
        );
        assertEquals(java.util.List.of("mariadb@nova.io", true), statement.bindings());
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
    void selectAppendsForUpdateClauseForMariaDbQuotedSelect() {
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
    void rendersTableGeneratorIncrementSqlWithBacktickIdentifiers() {
        // MariaDbDialect는 tableGeneratorIncrementSql을 override하지 않고 Dialect 기본 구현을 그대로 쓴다 —
        // quote()가 backtick을 반환하므로 상속된 기본 구현도 MariaDB에 맞는 식별자 quoting을 낸다는 것을 잠근다.
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
    void rendersElementCollectionValueColumnTypesByStorageType() {
        EntityMetadataFactory factory = new EntityMetadataFactory(new DefaultNamingStrategy());
        EntityMetadata<EcHolder> holder = factory.getEntityMetadata(EcHolder.class);

        String stringColorsDdl = dialect.schemaGenerator().createCollectionTable(
                holder.findProperty("stringColors").orElseThrow()
                        .elementCollectionInfo().toCollectionTableDefinition(Long.class));
        org.junit.jupiter.api.Assertions.assertTrue(
                stringColorsDdl.contains("`string_colors` varchar(255)"), stringColorsDdl);

        String ordinalColorsDdl = dialect.schemaGenerator().createCollectionTable(
                holder.findProperty("ordinalColors").orElseThrow()
                        .elementCollectionInfo().toCollectionTableDefinition(Long.class));
        org.junit.jupiter.api.Assertions.assertTrue(
                ordinalColorsDdl.contains("`ordinal_colors` integer"), ordinalColorsDdl);

        String refsDdl = dialect.schemaGenerator().createCollectionTable(
                holder.findProperty("refs").orElseThrow()
                        .elementCollectionInfo().toCollectionTableDefinition(Long.class));
        org.junit.jupiter.api.Assertions.assertTrue(refsDdl.contains("`refs` varchar(255)"), refsDdl);
    }

    @Test
    void rendersScalarUuidFloatAndShortColumnsByStorageType() {
        EntityMetadata<ScalarHolder> holder = new EntityMetadataFactory(new DefaultNamingStrategy())
                .getEntityMetadata(ScalarHolder.class);
        String ddl = dialect.schemaGenerator().createTable(holder);
        // UUID 스칼라는 저장타입 String → varchar (UuidStringConverter), Float → real, Short → smallint.
        org.junit.jupiter.api.Assertions.assertTrue(ddl.contains("`uid` varchar(255)"), ddl);
        org.junit.jupiter.api.Assertions.assertTrue(ddl.contains("`ratio` real"), ddl);
        org.junit.jupiter.api.Assertions.assertTrue(ddl.contains("`level` smallint"), ddl);
    }

    @Test
    void rendersToOneForeignKeyColumnsByReferencedIdStorageType() {
        EntityMetadataFactory factory = new EntityMetadataFactory(new DefaultNamingStrategy());
        String ddl = dialect.schemaGenerator().createTable(factory.getEntityMetadata(FkChild.class));
        // to-one FK 컬럼은 참조 @Id 저장타입을 따른다: UUID→varchar, Integer→integer, Long→bigint.
        org.junit.jupiter.api.Assertions.assertTrue(ddl.contains("`uuid_ref` varchar(255)"), ddl);
        org.junit.jupiter.api.Assertions.assertTrue(ddl.contains("`int_ref` integer"), ddl);
        org.junit.jupiter.api.Assertions.assertTrue(ddl.contains("`long_ref` bigint"), ddl);
    }

    @jakarta.persistence.Entity
    @jakarta.persistence.Table(name = "fk_uuid_parent")
    static class FkUuidParent {
        @jakarta.persistence.Id
        java.util.UUID id;
    }

    @jakarta.persistence.Entity
    @jakarta.persistence.Table(name = "fk_integer_parent")
    static class FkIntegerParent {
        @jakarta.persistence.Id
        Integer id;
    }

    @jakarta.persistence.Entity
    @jakarta.persistence.Table(name = "fk_long_parent")
    static class FkLongParent {
        @jakarta.persistence.Id
        Long id;
    }

    @jakarta.persistence.Entity
    @jakarta.persistence.Table(name = "fk_child")
    static class FkChild {
        @jakarta.persistence.Id
        Long id;

        @jakarta.persistence.ManyToOne(targetEntity = FkUuidParent.class)
        @jakarta.persistence.JoinColumn(name = "uuid_ref")
        FkUuidParent uuidRef;

        @jakarta.persistence.ManyToOne(targetEntity = FkIntegerParent.class)
        @jakarta.persistence.JoinColumn(name = "int_ref")
        FkIntegerParent intRef;

        @jakarta.persistence.ManyToOne(targetEntity = FkLongParent.class)
        @jakarta.persistence.JoinColumn(name = "long_ref")
        FkLongParent longRef;
    }

    @jakarta.persistence.Entity
    @jakarta.persistence.Table(name = "scalar_holder")
    static class ScalarHolder {
        @jakarta.persistence.Id
        Long id;
        java.util.UUID uid;
        Float ratio;
        Short level;
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
