package io.nova.sql;

import io.nova.metadata.DefaultNamingStrategy;
import io.nova.metadata.EntityMetadata;
import io.nova.metadata.EntityMetadataFactory;
import io.nova.metadata.PersistentProperty;
import io.nova.support.fixtures.FixtureEntities.AlterTargetEntity;
import io.nova.support.fixtures.FixtureEntities.AutoNamedIndexEntity;
import io.nova.support.fixtures.FixtureEntities.EnumOrdinalAccount;
import io.nova.support.fixtures.FixtureEntities.EnumStringAccount;
import io.nova.support.fixtures.FixtureEntities.RepeatedIndexEntity;
import io.nova.support.fixtures.FixtureEntities.SampleAccount;
import io.nova.support.fixtures.FixtureEntities.SingleIndexEntity;
import io.nova.support.fixtures.FixtureEntities.SingleUniqueConstraintEntity;
import io.nova.support.fixtures.FixtureEntities.UnsupportedTypeEntity;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    void rejectsUnsupportedJavaTypes() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> dialect.schemaGenerator().createTable(
                        factory.getEntityMetadata(UnsupportedTypeEntity.class)
                )
        );

        assertEquals("Unsupported column type: java.math.BigDecimal", exception.getMessage());
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
}
