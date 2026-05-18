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
    void rendersPartialUpdateWithSingleField() {
        SqlStatement statement = dialect.sqlRenderer().update(
                metadata,
                new SampleAccount(5L, "x@nova.io", true),
                java.util.List.of("email")
        );

        assertEquals("update accounts set email_address = ? where id = ?", statement.sql());
        assertEquals(java.util.List.of("x@nova.io", 5L), statement.bindings());
    }

    @Test
    void rendersPartialUpdateWithMultipleFieldsInDeclaredOrder() {
        SqlStatement statement = dialect.sqlRenderer().update(
                metadata,
                new SampleAccount(5L, "x@nova.io", false),
                java.util.List.of("active", "email")
        );

        assertEquals("update accounts set active = ?, email_address = ? where id = ?", statement.sql());
        assertEquals(java.util.List.of(false, "x@nova.io", 5L), statement.bindings());
    }

    @Test
    void partialUpdateDedupsRepeatedField() {
        SqlStatement statement = dialect.sqlRenderer().update(
                metadata,
                new SampleAccount(5L, "x@nova.io", true),
                java.util.List.of("email", "email", "active")
        );

        assertEquals("update accounts set email_address = ?, active = ? where id = ?", statement.sql());
        assertEquals(java.util.List.of("x@nova.io", true, 5L), statement.bindings());
    }

    @Test
    void partialUpdateRejectsEmptyFields() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> dialect.sqlRenderer().update(
                        metadata,
                        new SampleAccount(5L, "x@nova.io", true),
                        java.util.List.of()
                )
        );

        assertEquals("update requires at least one field", exception.getMessage());
    }

    @Test
    void partialUpdateRejectsUnknownField() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> dialect.sqlRenderer().update(
                        metadata,
                        new SampleAccount(5L, "x@nova.io", true),
                        java.util.List.of("notAField")
                )
        );

        assertEquals("Unknown property notAField on " + SampleAccount.class.getName(), exception.getMessage());
    }

    @Test
    void partialUpdateRejectsIdField() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> dialect.sqlRenderer().update(
                        metadata,
                        new SampleAccount(5L, "x@nova.io", true),
                        java.util.List.of("id")
                )
        );

        assertEquals("Cannot update id property: id", exception.getMessage());
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
    void rendersInOperatorWithMultipleValues() {
        SqlStatement statement = dialect.sqlRenderer().select(
                metadata,
                QuerySpec.empty().where(Criteria.in("id", java.util.List.of(1L, 2L, 3L)))
        );

        assertEquals(
                "select id as id, email_address as email_address, active as active from accounts where id in (?, ?, ?)",
                statement.sql()
        );
        assertEquals(java.util.List.of(1L, 2L, 3L), statement.bindings());
    }

    @Test
    void rendersEmptyInListAsAlwaysFalsePredicate() {
        SqlStatement statement = dialect.sqlRenderer().select(
                metadata,
                QuerySpec.empty().where(Criteria.in("id", java.util.List.of()))
        );

        assertEquals(
                "select id as id, email_address as email_address, active as active from accounts where 1 = 0",
                statement.sql()
        );
        assertEquals(java.util.List.of(), statement.bindings());
    }

    @Test
    void rejectsNullElementInInList() {
        java.util.List<Object> idsWithNull = new java.util.ArrayList<>();
        idsWithNull.add(1L);
        idsWithNull.add(null);
        idsWithNull.add(3L);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> Criteria.in("id", idsWithNull)
        );

        assertEquals("Criteria.in value at index 1 for property id is null", exception.getMessage());
    }

    @Test
    void rendersNotInOperatorWithMultipleValues() {
        SqlStatement statement = dialect.sqlRenderer().select(
                metadata,
                QuerySpec.empty().where(Criteria.notIn("id", java.util.List.of(1L, 2L, 3L)))
        );

        assertEquals(
                "select id as id, email_address as email_address, active as active from accounts where id not in (?, ?, ?)",
                statement.sql()
        );
        assertEquals(java.util.List.of(1L, 2L, 3L), statement.bindings());
    }

    @Test
    void rendersEmptyNotInListAsAlwaysTruePredicate() {
        SqlStatement statement = dialect.sqlRenderer().select(
                metadata,
                QuerySpec.empty().where(Criteria.notIn("id", java.util.List.of()))
        );

        assertEquals(
                "select id as id, email_address as email_address, active as active from accounts where 1 = 1",
                statement.sql()
        );
        assertEquals(java.util.List.of(), statement.bindings());
    }

    @Test
    void rejectsNullElementInNotInList() {
        java.util.List<Object> idsWithNull = new java.util.ArrayList<>();
        idsWithNull.add(1L);
        idsWithNull.add(null);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> Criteria.notIn("id", idsWithNull)
        );

        assertEquals("Criteria.notIn value at index 1 for property id is null", exception.getMessage());
    }

    @Test
    void rendersBetweenOperator() {
        SqlStatement statement = dialect.sqlRenderer().select(
                metadata,
                QuerySpec.empty().where(Criteria.between("id", 10L, 20L))
        );

        assertEquals(
                "select id as id, email_address as email_address, active as active from accounts where id between ? and ?",
                statement.sql()
        );
        assertEquals(java.util.List.of(10L, 20L), statement.bindings());
    }

    @Test
    void rejectsNullLowOrHighInBetween() {
        assertThrows(NullPointerException.class, () -> Criteria.between("id", null, 20L));
        assertThrows(NullPointerException.class, () -> Criteria.between("id", 10L, null));
    }

    @Test
    void rendersNegationOfCompoundPredicate() {
        SqlStatement statement = dialect.sqlRenderer().select(
                metadata,
                QuerySpec.empty().where(Criteria.not(Criteria.and(
                        Criteria.eq("email", "a@nova.io"),
                        Criteria.eq("active", true)
                )))
        );

        assertEquals(
                "select id as id, email_address as email_address, active as active from accounts where not ((email_address = ?) and (active = ?))",
                statement.sql()
        );
        assertEquals(java.util.List.of("a@nova.io", true), statement.bindings());
    }

    @Test
    void rejectsNullInnerForNot() {
        assertThrows(NullPointerException.class, () -> Criteria.not(null));
    }

    @Test
    void rendersDeleteByIdsAsSingleInDelete() {
        SqlStatement statement = dialect.sqlRenderer().deleteByIds(metadata, java.util.List.of(10L, 20L, 30L));

        assertEquals("delete from accounts where id in (?, ?, ?)", statement.sql());
        assertEquals(java.util.List.of(10L, 20L, 30L), statement.bindings());
    }

    @Test
    void deleteByIdsRejectsEmptyList() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> dialect.sqlRenderer().deleteByIds(metadata, java.util.List.of())
        );

        assertEquals("deleteByIds requires at least one id", exception.getMessage());
    }

    @Test
    void rendersDeleteByQueryWithCompoundPredicate() {
        SqlStatement statement = dialect.sqlRenderer().deleteByQuery(
                metadata,
                QuerySpec.empty().where(Criteria.and(
                        Criteria.eq("active", false),
                        Criteria.isNull("email")
                ))
        );

        assertEquals(
                "delete from accounts where (active = ?) and (email_address is null)",
                statement.sql()
        );
        assertEquals(java.util.List.of(false), statement.bindings());
    }

    @Test
    void deleteByQueryRejectsNullPredicate() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> dialect.sqlRenderer().deleteByQuery(metadata, QuerySpec.empty())
        );

        assertEquals("deleteByQuery requires a non-null predicate", exception.getMessage());
    }

    @Test
    void deleteByQueryRejectsSort() {
        QuerySpec spec = QuerySpec.empty()
                .where(Criteria.eq("active", false))
                .orderBy(Sort.by(Sort.Order.asc("id")));

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> dialect.sqlRenderer().deleteByQuery(metadata, spec)
        );

        assertEquals("deleteByQuery does not support sort", exception.getMessage());
    }

    @Test
    void deleteByQueryRejectsPageable() {
        QuerySpec spec = QuerySpec.empty()
                .where(Criteria.eq("active", false))
                .page(Pageable.of(10, 0));

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> dialect.sqlRenderer().deleteByQuery(metadata, spec)
        );

        assertEquals("deleteByQuery does not support pageable", exception.getMessage());
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
