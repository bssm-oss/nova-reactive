package io.nova.sql;

import io.nova.metadata.DefaultNamingStrategy;
import io.nova.metadata.EntityMetadata;
import io.nova.metadata.EntityMetadataFactory;
import io.nova.query.Criteria;
import io.nova.query.Pageable;
import io.nova.query.QuerySpec;
import io.nova.query.Sort;
import io.nova.support.fixtures.FixtureEntities.SampleAccount;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AbstractSqlRendererTest {
    private final EntityMetadata<SampleAccount> metadata = new EntityMetadataFactory(new DefaultNamingStrategy())
            .getEntityMetadata(SampleAccount.class);
    private final Dialect dialect = new TestDialect();

    @Test
    void rendersInsertForNonGeneratedColumns() {
        SqlStatement statement = dialect.sqlRenderer().insert(metadata, new SampleAccount(null, "a@nova.io", true));

        assertEquals("insert into accounts (email_address, active) values (?, ?)", statement.sql());
        assertEquals(java.util.List.of("a@nova.io", true), statement.bindings());
    }

    @Test
    void rendersUpdateStatements() {
        SqlStatement statement = dialect.sqlRenderer().update(metadata, new SampleAccount(5L, "a@nova.io", false));

        assertEquals("update accounts set email_address = ?, active = ? where id = ?", statement.sql());
        assertEquals(java.util.List.of("a@nova.io", false, 5L), statement.bindings());
    }

    @Test
    void rendersCompoundPredicatesAndPaging() {
        SqlStatement statement = dialect.sqlRenderer().select(
                metadata,
                QuerySpec.empty()
                        .where(Criteria.and(
                                Criteria.eq("email", "a@nova.io"),
                                Criteria.or(Criteria.isNull("email"), Criteria.eq("active", true))
                        ))
                        .orderBy(Sort.by(Sort.Order.desc("id")))
                        .page(Pageable.of(5, 10))
        );

        assertEquals(
                "select id as id, email_address as email_address, active as active from accounts where (email_address = ?) and ((email_address is null) or (active = ?)) order by id desc limit ? offset ?",
                statement.sql()
        );
        assertEquals(java.util.List.of("a@nova.io", true, 5, 10L), statement.bindings());
    }

    @Test
    void rendersCountAndExistsQueries() {
        SqlStatement count = dialect.sqlRenderer().count(metadata, QuerySpec.empty().where(Criteria.eq("active", true)));
        SqlStatement exists = dialect.sqlRenderer().exists(metadata, QuerySpec.empty().where(Criteria.isNotNull("email")));

        assertEquals("select count(*) as count from accounts where active = ?", count.sql());
        assertEquals(java.util.List.of(true), count.bindings());
        assertEquals("select 1 from accounts where email_address is not null limit 1", exists.sql());
    }

    @Test
    void rejectsUnknownPropertiesInPredicates() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> dialect.sqlRenderer().select(metadata, QuerySpec.empty().where(Criteria.eq("missing", "x")))
        );

        assertEquals("Unknown property missing on " + SampleAccount.class.getName(), exception.getMessage());
    }

    @Test
    void rejectsUnknownPropertiesInSorts() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> dialect.sqlRenderer().select(metadata, QuerySpec.empty().orderBy(Sort.by(Sort.Order.asc("missing"))))
        );

        assertEquals("Unknown property missing on " + SampleAccount.class.getName(), exception.getMessage());
    }

    private static final class TestDialect implements Dialect {
        private final BindMarkerStrategy bindMarkers = index -> "?";
        private final SqlRenderer renderer = new AbstractSqlRenderer(this) {
        };
        private final SchemaGenerator schemaGenerator = metadata -> "create table " + metadata.tableName();

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
