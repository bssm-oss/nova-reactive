package io.nova.sql;

import io.nova.metadata.DefaultNamingStrategy;
import io.nova.metadata.EntityMetadataFactory;
import io.nova.support.fixtures.FixtureEntities.SampleAccount;
import io.nova.support.fixtures.FixtureEntities.UnsupportedTypeEntity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AbstractSchemaGeneratorTest {
    private final Dialect dialect = new TestDialect();

    @Test
    void rendersCreateTableSql() {
        String statement = dialect.schemaGenerator().createTable(
                new EntityMetadataFactory(new DefaultNamingStrategy()).getEntityMetadata(SampleAccount.class)
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
                        new EntityMetadataFactory(new DefaultNamingStrategy()).getEntityMetadata(UnsupportedTypeEntity.class)
                )
        );

        assertEquals("Unsupported column type: java.math.BigDecimal", exception.getMessage());
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
