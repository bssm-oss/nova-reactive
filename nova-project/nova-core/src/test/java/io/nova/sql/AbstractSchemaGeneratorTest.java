package io.nova.sql;

import io.nova.metadata.DefaultNamingStrategy;
import io.nova.metadata.EntityMetadata;
import io.nova.metadata.EntityMetadataFactory;
import io.nova.metadata.PersistentProperty;
import io.nova.support.fixtures.FixtureEntities.AlterTargetEntity;
import io.nova.support.fixtures.FixtureEntities.AuthorWithBooksAnnotated;
import io.nova.support.fixtures.FixtureEntities.BookWithAuthorAnnotated;
import io.nova.support.fixtures.FixtureEntities.AutoNamedIndexEntity;
import io.nova.support.fixtures.FixtureEntities.ColumnTypedEntity;
import io.nova.support.fixtures.FixtureEntities.ColumnUniqueEntity;
import io.nova.support.fixtures.FixtureEntities.ColumnDefinitionEntity;
import io.nova.support.fixtures.FixtureEntities.SchemaQualifiedEntity;
import io.nova.support.fixtures.FixtureEntities.LobEntity;
import io.nova.support.fixtures.FixtureEntities.EnumOrdinalAccount;
import io.nova.support.fixtures.FixtureEntities.EnumStringAccount;
import io.nova.support.fixtures.FixtureEntities.JsonAccount;
import io.nova.support.fixtures.FixtureEntities.RepeatedIndexEntity;
import io.nova.support.fixtures.FixtureEntities.SampleAccount;
import io.nova.support.fixtures.FixtureEntities.SingleIndexEntity;
import io.nova.support.fixtures.FixtureEntities.SingleUniqueConstraintEntity;
import io.nova.support.fixtures.FixtureEntities.TemporalEvent;
import io.nova.support.fixtures.FixtureEntities.UnsupportedTypeEntity;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AbstractSchemaGeneratorTest {
    private final Dialect dialect = new TestDialect();
    private final EntityMetadataFactory factory = new EntityMetadataFactory(new DefaultNamingStrategy());

    @Test
    void rendersCreateTableSql() {
        String statement = dialect.schemaGenerator().createTable(
                factory.getEntityMetadata(SampleAccount.class)
        );

        assertEquals(
                "create table accounts (id bigint primary key, email_address varchar(255), active boolean not null)",
                statement
        );
    }

    @Test
    void rendersCreateTableIfNotExistsSql() {
        String statement = dialect.schemaGenerator().createTableIfNotExists(
                factory.getEntityMetadata(SampleAccount.class)
        );

        assertEquals(
                "create table if not exists accounts (id bigint primary key, email_address varchar(255), active boolean not null)",
                statement
        );
    }

    @Test
    void rendersDropTableSql() {
        String statement = dialect.schemaGenerator().dropTable(
                factory.getEntityMetadata(SampleAccount.class)
        );

        assertEquals("drop table accounts", statement);
    }

    @Test
    void rendersDropTableIfExistsSql() {
        String statement = dialect.schemaGenerator().dropTableIfExists(
                factory.getEntityMetadata(SampleAccount.class)
        );

        assertEquals("drop table if exists accounts", statement);
    }

    @Test
    void createTableSkipsOneToManyInverseSideAndIncludesManyToOneFkColumn() {
        // OneToMany inverse 필드(List<Book> books)는 부모 테이블에 컬럼을 만들지 않아야 한다.
        // raw properties()를 사용하던 시절에는 List 타입을 sqlType에 넘겨 IllegalArgumentException이 났다.
        String parent = dialect.schemaGenerator().createTable(
                factory.getEntityMetadata(AuthorWithBooksAnnotated.class)
        );
        assertEquals(
                "create table annotated_authors (id bigint primary key, name varchar(255))",
                parent
        );

        // ManyToOne owning 필드는 FK 컬럼으로 매핑된다.
        String child = dialect.schemaGenerator().createTable(
                factory.getEntityMetadata(BookWithAuthorAnnotated.class)
        );
        assertEquals(
                "create table annotated_books (id bigint primary key, title varchar(255), author_id bigint)",
                child
        );
    }

    @Test
    void rejectsUnsupportedJavaTypes() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> dialect.schemaGenerator().createTable(
                        factory.getEntityMetadata(UnsupportedTypeEntity.class)
                )
        );

        assertEquals("Unsupported column type: java.util.Locale", exception.getMessage());
    }

    @Test
    void rendersTemporalColumnsAsDateTimeAndTimestamp() {
        String statement = dialect.schemaGenerator().createTable(
                factory.getEntityMetadata(TemporalEvent.class)
        );

        // @Temporal(java.util.Date/Calendar)은 저장 타입(LocalDate/LocalTime/LocalDateTime)을 거쳐
        // 기본 dialect 토큰 date/time/timestamp 컬럼으로 생성된다.
        assertTrue(statement.contains("event_date date"), statement);
        assertTrue(statement.contains("event_time time"), statement);
        assertTrue(statement.contains("event_timestamp timestamp"), statement);
        assertTrue(statement.contains("scheduled_at timestamp"), statement);
    }

    @Test
    void rendersColumnLengthPrecisionAndScale() {
        String statement = dialect.schemaGenerator().createTable(
                factory.getEntityMetadata(ColumnTypedEntity.class)
        );

        assertEquals(
                "create table column_typed ("
                        + "id bigint primary key, "
                        + "short_name varchar(64), "
                        + "description varchar(255), "
                        + "price numeric(12, 2), "
                        + "default_decimal numeric(19, 2), "
                        // scale 생략 시 numeric(precision, 0) — 소수부 없는 정수 numeric으로 emit된다.
                        + "precision_only numeric(10, 0))",
                statement
        );
    }

    @Test
    void createIndexesRendersSecondaryIndexSql() {
        List<String> statements = dialect.schemaGenerator().createIndexes(
                factory.getEntityMetadata(SingleIndexEntity.class)
        );

        assertEquals(1, statements.size());
        assertEquals("create index ix_indexed_email on indexed_accounts (email)", statements.get(0));
    }

    @Test
    void createIndexesRendersUniqueConstraintAsUniqueIndex() {
        List<String> statements = dialect.schemaGenerator().createIndexes(
                factory.getEntityMetadata(SingleUniqueConstraintEntity.class)
        );

        assertEquals(1, statements.size());
        assertEquals("create unique index uk_email on unique_accounts (email)", statements.get(0));
    }

    @Test
    void rendersUniqueColumnConstraint() {
        String sql = dialect.schemaGenerator().createTable(factory.getEntityMetadata(ColumnUniqueEntity.class));
        assertTrue(sql.contains("email varchar(255) unique"), sql);
    }

    @Test
    void rendersColumnDefinitionOverride() {
        String sql = dialect.schemaGenerator().createTable(factory.getEntityMetadata(ColumnDefinitionEntity.class));
        // columnDefinition="text"이 dialect 유도 타입(varchar(255)) 대신 그대로 쓰인다.
        assertTrue(sql.contains("note text"), sql);
    }

    @Test
    void rendersLobColumnTypes() {
        String sql = dialect.schemaGenerator().createTable(factory.getEntityMetadata(LobEntity.class));
        // 기본 dialect lobType: 문자 LOB=clob, 바이너리 LOB=blob.
        assertTrue(sql.contains("content clob"), sql);
        assertTrue(sql.contains("data blob"), sql);
    }

    @Test
    void rendersSchemaQualifiedTableName() {
        String sql = dialect.schemaGenerator().createTable(factory.getEntityMetadata(SchemaQualifiedEntity.class));
        // @Table(schema="app") -> 스키마 한정 테이블 참조.
        assertTrue(sql.contains("app.accounts"), sql);
    }

    @Test
    void createIndexesCombinesIndexesAndUniqueConstraintsForSameEntity() {
        EntityMetadata<RepeatedIndexEntity> metadata = factory.getEntityMetadata(RepeatedIndexEntity.class);
        List<String> statements = dialect.schemaGenerator().createIndexes(metadata);

        assertEquals(2, statements.size());
        assertTrue(statements.get(0).startsWith("create index "));
        assertTrue(statements.get(1).startsWith("create index "));
    }

    @Test
    void createIndexesRendersCompositeColumnsInDeclaredOrder() {
        List<String> statements = dialect.schemaGenerator().createIndexes(
                factory.getEntityMetadata(AutoNamedIndexEntity.class)
        );

        assertEquals(1, statements.size());
        assertEquals(
                "create index ix_multi_indexed_accounts_first_name_last_name "
                        + "on multi_indexed_accounts (first_name, last_name)",
                statements.get(0)
        );
    }

    @Test
    void createIndexesReturnsEmptyListForEntityWithoutIndexes() {
        List<String> statements = dialect.schemaGenerator().createIndexes(
                factory.getEntityMetadata(SampleAccount.class)
        );

        assertTrue(statements.isEmpty());
    }

    @Test
    void alterTableAddColumnRendersAddColumnStatement() {
        EntityMetadata<AlterTargetEntity> metadata = factory.getEntityMetadata(AlterTargetEntity.class);
        PersistentProperty emailProperty = metadata.findProperty("email").orElseThrow();

        String statement = dialect.schemaGenerator().alterTableAddColumn(metadata, emailProperty);

        assertEquals("alter table alter_target add column email varchar(255)", statement);
    }

    @Test
    void alterTableDropColumnRendersDropColumnStatement() {
        EntityMetadata<AlterTargetEntity> metadata = factory.getEntityMetadata(AlterTargetEntity.class);

        String statement = dialect.schemaGenerator().alterTableDropColumn(metadata, "email");

        assertEquals("alter table alter_target drop column email", statement);
    }

    @Test
    void rendersEnumeratedStringPropertyAsVarchar() {
        String statement = dialect.schemaGenerator().createTable(
                factory.getEntityMetadata(EnumStringAccount.class)
        );

        assertEquals(
                "create table enum_string_accounts (id bigint primary key, status varchar(255))",
                statement
        );
    }

    @Test
    void rendersEnumeratedOrdinalPropertyAsInteger() {
        String statement = dialect.schemaGenerator().createTable(
                factory.getEntityMetadata(EnumOrdinalAccount.class)
        );

        assertEquals(
                "create table enum_ordinal_accounts (id bigint primary key, status integer)",
                statement
        );
    }

    @Test
    void rendersJsonPropertyWithDefaultDialectJsonType() {
        String statement = dialect.schemaGenerator().createTable(
                factory.getEntityMetadata(JsonAccount.class)
        );

        assertEquals(
                "create table json_accounts (id bigint primary key, email_address varchar(255), preferences json)",
                statement
        );
    }

    @Test
    void rendersJsonPropertyWithDialectOverriddenJsonType() {
        Dialect jsonb = new JsonbTestDialect();
        String statement = jsonb.schemaGenerator().createTable(
                factory.getEntityMetadata(JsonAccount.class)
        );

        assertEquals(
                "create table json_accounts (id bigint primary key, email_address varchar(255), preferences jsonb)",
                statement
        );
    }

    @Test
    void alterTableDropColumnRejectsUnknownColumn() {
        EntityMetadata<AlterTargetEntity> metadata = factory.getEntityMetadata(AlterTargetEntity.class);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> dialect.schemaGenerator().alterTableDropColumn(metadata, "legacy_column")
        );

        assertTrue(exception.getMessage().contains("legacy_column"),
                "exception should name the rejected column, got " + exception.getMessage());
        assertTrue(exception.getMessage().contains("email"),
                "exception should list known columns, got " + exception.getMessage());
    }

    @Test
    void mergedHierarchyCreateTableEmitsUnionColumnsAndDiscriminator() {
        factory.getEntityMetadata(io.nova.support.fixtures.FixtureEntities.Car.class);
        factory.getEntityMetadata(io.nova.support.fixtures.FixtureEntities.Truck.class);
        EntityMetadata<?> merged =
                factory.mergedHierarchyMetadata(io.nova.support.fixtures.FixtureEntities.Vehicle.class);

        String ddl = dialect.schemaGenerator().createTable(merged);

        // 단일 테이블에 루트(id/name) + 모든 서브타입(doors/payload) + discriminator(kind) 컬럼이 들어간다.
        assertTrue(ddl.startsWith("create table vehicles ("), ddl);
        assertTrue(ddl.contains("doors integer"), "Dog 서브타입 컬럼이 nullable로 포함, got " + ddl);
        assertTrue(ddl.contains("payload double precision"), "Truck 서브타입 컬럼 포함, got " + ddl);
        assertTrue(ddl.contains("kind varchar(31) not null"), "discriminator 컬럼 포함, got " + ddl);
        // 서브타입 전용 컬럼은 not null이 붙지 않는다(단일 테이블에서 nullable).
        assertFalse(ddl.contains("doors integer not null"), "서브타입 전용 컬럼은 nullable이어야 한다, got " + ddl);
    }

    @Test
    void addForeignKeyRendersNamedAnsiConstraint() {
        String ddl = dialect.schemaGenerator().addForeignKey(new io.nova.metadata.ForeignKeyDefinition(
                "fk_child_constrained", "fk_child_parent",
                List.of("parent_id"), "fk_parent", List.of("id")));

        assertEquals(
                "alter table fk_child_constrained add constraint fk_child_parent"
                        + " foreign key (parent_id) references fk_parent (id)",
                ddl);
    }

    @Test
    void addForeignKeyGeneratesDeterministicNameWhenUnnamed() {
        String ddl = dialect.schemaGenerator().addForeignKey(new io.nova.metadata.ForeignKeyDefinition(
                "fk_child_constrained", "",
                List.of("parent_id"), "fk_parent", List.of("id")));

        assertEquals(
                "alter table fk_child_constrained add constraint fk_fk_child_constrained_parent_id"
                        + " foreign key (parent_id) references fk_parent (id)",
                ddl);
    }

    private static final class TestDialect implements Dialect {
        private final BindMarkerStrategy bindMarkers = index -> "?";
        private final SqlRenderer renderer = new AbstractSqlRenderer(this) {
        };
        private final SchemaGenerator schemaGenerator = new AbstractSchemaGenerator(this) {
        };

        @Override
        public String name() {
            return "test";
        }

        @Override
        public String quote(String identifier) {
            return identifier;
        }

        @Override
        public BindMarkerStrategy bindMarkers() {
            return bindMarkers;
        }

        @Override
        public SqlRenderer sqlRenderer() {
            return renderer;
        }

        @Override
        public SchemaGenerator schemaGenerator() {
            return schemaGenerator;
        }
    }

    /**
     * {@link Dialect#jsonColumnType()}을 {@code jsonb}로 override 하는 dialect double로, PostgreSQL의
     * jsonb 동작을 nova-core 안에서 module dependency 없이 재현한다. PostgresqlDialect 자체 검증은
     * postgresql 모듈 테스트가 담당한다.
     */
    private static final class JsonbTestDialect implements Dialect {
        private final BindMarkerStrategy bindMarkers = index -> "?";
        private final SqlRenderer renderer = new AbstractSqlRenderer(this) {
        };
        private final SchemaGenerator schemaGenerator = new AbstractSchemaGenerator(this) {
        };

        @Override
        public String name() {
            return "jsonb-test";
        }

        @Override
        public String quote(String identifier) {
            return identifier;
        }

        @Override
        public BindMarkerStrategy bindMarkers() {
            return bindMarkers;
        }

        @Override
        public SqlRenderer sqlRenderer() {
            return renderer;
        }

        @Override
        public SchemaGenerator schemaGenerator() {
            return schemaGenerator;
        }

        @Override
        public String jsonColumnType() {
            return "jsonb";
        }
    }
}
