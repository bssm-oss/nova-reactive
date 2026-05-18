package io.nova.sql;

import io.nova.metadata.DefaultNamingStrategy;
import io.nova.metadata.EntityMetadata;
import io.nova.metadata.EntityMetadataFactory;
import io.nova.query.Criteria;
import io.nova.query.Pageable;
import io.nova.query.QuerySpec;
import io.nova.query.Sort;
import io.nova.support.fixtures.FixtureEntities.IntegerVersionedAccount;
import io.nova.support.fixtures.FixtureEntities.SampleAccount;
import io.nova.support.fixtures.FixtureEntities.ShortVersionedAccount;
import io.nova.support.fixtures.FixtureEntities.SoftDeletableAccount;
import io.nova.support.fixtures.FixtureEntities.VersionedAccount;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AbstractSqlRendererTest {
    private final EntityMetadataFactory metadataFactory = new EntityMetadataFactory(new DefaultNamingStrategy());
    private final EntityMetadata<SampleAccount> metadata = metadataFactory.getEntityMetadata(SampleAccount.class);
    private final EntityMetadata<SoftDeletableAccount> softMetadata = metadataFactory.getEntityMetadata(SoftDeletableAccount.class);
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
    void rendersUpdateByQueryWithLinkedHashMapOrder() {
        java.util.LinkedHashMap<String, Object> fields = new java.util.LinkedHashMap<>();
        fields.put("email", "x@nova.io");
        fields.put("active", true);

        SqlStatement statement = dialect.sqlRenderer().updateByQuery(
                metadata,
                fields,
                QuerySpec.empty().where(Criteria.gte("id", 10L))
        );

        assertEquals(
                "update accounts set email_address = ?, active = ? where id >= ?",
                statement.sql()
        );
        assertEquals(java.util.List.of("x@nova.io", true, 10L), statement.bindings());
    }

    @Test
    void updateByQueryRejectsEmptyFieldValues() {
        java.util.LinkedHashMap<String, Object> empty = new java.util.LinkedHashMap<>();

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> dialect.sqlRenderer().updateByQuery(metadata, empty, QuerySpec.empty().where(Criteria.eq("id", 1L)))
        );

        assertEquals("updateByQuery requires at least one field assignment", exception.getMessage());
    }

    @Test
    void updateByQueryRejectsNullPredicate() {
        java.util.LinkedHashMap<String, Object> fields = new java.util.LinkedHashMap<>();
        fields.put("email", "x@nova.io");

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> dialect.sqlRenderer().updateByQuery(metadata, fields, QuerySpec.empty())
        );

        assertEquals("updateByQuery requires a non-null where predicate", exception.getMessage());
    }

    @Test
    void updateByQueryRejectsSort() {
        java.util.LinkedHashMap<String, Object> fields = new java.util.LinkedHashMap<>();
        fields.put("email", "x@nova.io");

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> dialect.sqlRenderer().updateByQuery(
                        metadata,
                        fields,
                        QuerySpec.empty().where(Criteria.eq("id", 1L)).orderBy(Sort.by(Sort.Order.asc("id")))
                )
        );

        assertEquals("updateByQuery does not support sort", exception.getMessage());
    }

    @Test
    void updateByQueryRejectsPageable() {
        java.util.LinkedHashMap<String, Object> fields = new java.util.LinkedHashMap<>();
        fields.put("email", "x@nova.io");

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> dialect.sqlRenderer().updateByQuery(
                        metadata,
                        fields,
                        QuerySpec.empty().where(Criteria.eq("id", 1L)).page(Pageable.of(10, 0))
                )
        );

        assertEquals("updateByQuery does not support pageable", exception.getMessage());
    }

    @Test
    void updateByQueryRejectsIdField() {
        java.util.LinkedHashMap<String, Object> fields = new java.util.LinkedHashMap<>();
        fields.put("id", 99L);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> dialect.sqlRenderer().updateByQuery(metadata, fields, QuerySpec.empty().where(Criteria.eq("email", "x@nova.io")))
        );

        assertEquals(
                "Cannot update id property id on " + SampleAccount.class.getName(),
                exception.getMessage()
        );
    }

    @Test
    void updateByQueryRejectsUnknownField() {
        java.util.LinkedHashMap<String, Object> fields = new java.util.LinkedHashMap<>();
        fields.put("nope", "value");

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> dialect.sqlRenderer().updateByQuery(metadata, fields, QuerySpec.empty().where(Criteria.eq("id", 1L)))
        );

        assertEquals(
                "Unknown property nope on " + SampleAccount.class.getName(),
                exception.getMessage()
        );
    }

    @Test
    void selectAutoAddsSoftDeleteAliveWhenNoPredicate() {
        SqlStatement statement = dialect.sqlRenderer().select(softMetadata, QuerySpec.empty());

        assertEquals(
                "select id as id, email_address as email_address, deleted_at as deleted_at from soft_deletable_accounts where deleted_at is null",
                statement.sql()
        );
        assertEquals(java.util.List.of(), statement.bindings());
    }

    @Test
    void selectAppendsSoftDeleteAliveAfterUserPredicate() {
        SqlStatement statement = dialect.sqlRenderer().select(
                softMetadata,
                QuerySpec.empty().where(Criteria.eq("email", "a@nova.io"))
        );

        assertEquals(
                "select id as id, email_address as email_address, deleted_at as deleted_at from soft_deletable_accounts where email_address = ? and deleted_at is null",
                statement.sql()
        );
        assertEquals(java.util.List.of("a@nova.io"), statement.bindings());
    }

    @Test
    void selectByIdAddsSoftDeleteAlive() {
        SqlStatement statement = dialect.sqlRenderer().selectById(softMetadata, 7L);

        assertEquals(
                "select id as id, email_address as email_address, deleted_at as deleted_at from soft_deletable_accounts where id = ? and deleted_at is null",
                statement.sql()
        );
        assertEquals(java.util.List.of(7L), statement.bindings());
    }

    @Test
    void countAddsSoftDeleteAlive() {
        SqlStatement statement = dialect.sqlRenderer().count(softMetadata, QuerySpec.empty());

        assertEquals("select count(*) as count from soft_deletable_accounts where deleted_at is null", statement.sql());
    }

    @Test
    void existsAddsSoftDeleteAlive() {
        SqlStatement statement = dialect.sqlRenderer().exists(softMetadata, QuerySpec.empty());

        assertEquals("select 1 from soft_deletable_accounts where deleted_at is null limit 1", statement.sql());
    }

    @Test
    void softDeleteByIdRendersUpdateWithAliveGuard() {
        Instant now = Instant.parse("2026-05-18T10:00:00Z");

        SqlStatement statement = dialect.sqlRenderer().softDeleteById(softMetadata, 7L, now);

        assertEquals(
                "update soft_deletable_accounts set deleted_at = ? where id = ? and deleted_at is null",
                statement.sql()
        );
        assertEquals(java.util.List.of(now, 7L), statement.bindings());
    }

    @Test
    void softDeleteByIdsRendersUpdateWithInAndAliveGuard() {
        Instant now = Instant.parse("2026-05-18T10:00:00Z");

        SqlStatement statement = dialect.sqlRenderer().softDeleteByIds(softMetadata, java.util.List.of(10L, 20L, 30L), now);

        assertEquals(
                "update soft_deletable_accounts set deleted_at = ? where id in (?, ?, ?) and deleted_at is null",
                statement.sql()
        );
        assertEquals(java.util.List.of(now, 10L, 20L, 30L), statement.bindings());
    }

    @Test
    void softDeleteByIdsRejectsEmptyList() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> dialect.sqlRenderer().softDeleteByIds(softMetadata, java.util.List.of(), Instant.EPOCH)
        );

        assertEquals("softDeleteByIds requires at least one id", exception.getMessage());
    }

    @Test
    void softDeleteByIdRejectsMetadataWithoutSoftDelete() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> dialect.sqlRenderer().softDeleteById(metadata, 1L, Instant.EPOCH)
        );

        assertEquals(
                "softDeleteById requires @SoftDelete on " + SampleAccount.class.getName(),
                exception.getMessage()
        );
    }

    @Test
    void rendersUpdateWithVersionIncrementAndWhereCheck() {
        EntityMetadata<VersionedAccount> versionedMetadata = new EntityMetadataFactory(new DefaultNamingStrategy())
                .getEntityMetadata(VersionedAccount.class);

        SqlStatement statement = dialect.sqlRenderer().update(
                versionedMetadata,
                new VersionedAccount(7L, "a@nova.io", 4L)
        );

        assertEquals(
                "update versioned_accounts set email_address = ?, version = ? where id = ? and version = ?",
                statement.sql()
        );
        assertEquals(java.util.List.of("a@nova.io", 5L, 7L, 4L), statement.bindings());
    }

    @Test
    void incrementsIntegerAndShortVersionInUpdate() {
        EntityMetadataFactory factory = new EntityMetadataFactory(new DefaultNamingStrategy());
        EntityMetadata<IntegerVersionedAccount> intMetadata = factory.getEntityMetadata(IntegerVersionedAccount.class);
        EntityMetadata<ShortVersionedAccount> shortMetadata = factory.getEntityMetadata(ShortVersionedAccount.class);

        SqlStatement intStatement = dialect.sqlRenderer().update(
                intMetadata,
                new IntegerVersionedAccount(1L, "a@nova.io", 2)
        );
        SqlStatement shortStatement = dialect.sqlRenderer().update(
                shortMetadata,
                new ShortVersionedAccount(1L, "a@nova.io", (short) 9)
        );

        assertEquals(java.util.List.of("a@nova.io", 3, 1L, 2), intStatement.bindings());
        assertEquals(java.util.List.of("a@nova.io", (short) 10, 1L, (short) 9), shortStatement.bindings());
    }

    @Test
    void rendersDeleteByEntityWithVersionCheck() {
        EntityMetadata<VersionedAccount> versionedMetadata = new EntityMetadataFactory(new DefaultNamingStrategy())
                .getEntityMetadata(VersionedAccount.class);

        SqlStatement statement = dialect.sqlRenderer().deleteByEntity(
                versionedMetadata,
                new VersionedAccount(7L, "a@nova.io", 4L)
        );

        assertEquals(
                "delete from versioned_accounts where id = ? and version = ?",
                statement.sql()
        );
        assertEquals(java.util.List.of(7L, 4L), statement.bindings());
    }

    @Test
    void deleteByEntityWithoutVersionFallsBackToIdOnly() {
        SqlStatement statement = dialect.sqlRenderer().deleteByEntity(
                metadata,
                new SampleAccount(7L, "a@nova.io", true)
        );

        assertEquals("delete from accounts where id = ?", statement.sql());
        assertEquals(java.util.List.of(7L), statement.bindings());
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

    @Test
    void rendersProjectionSelectListWithGivenFields() {
        SqlStatement statement = dialect.sqlRenderer().selectProjection(
                metadata,
                java.util.List.of("id", "email"),
                QuerySpec.empty()
        );

        assertEquals(
                "select id as id, email_address as email_address from accounts",
                statement.sql()
        );
        assertEquals(java.util.List.of(), statement.bindings());
    }

    @Test
    void projectionSelectPreservesFieldOrderAndAppliesWhereOrderByAndPaging() {
        SqlStatement statement = dialect.sqlRenderer().selectProjection(
                metadata,
                java.util.List.of("email", "id"),
                QuerySpec.empty()
                        .where(Criteria.eq("active", true))
                        .orderBy(Sort.by(Sort.Order.asc("id")))
                        .page(Pageable.of(10, 20))
        );

        assertEquals(
                "select email_address as email_address, id as id from accounts where active = ? order by id asc limit ? offset ?",
                statement.sql()
        );
        assertEquals(java.util.List.of(true, 10, 20L), statement.bindings());
    }

    @Test
    void projectionSelectAppendsSoftDeleteAliveWhenMetadataDeclaresIt() {
        SqlStatement statement = dialect.sqlRenderer().selectProjection(
                softMetadata,
                java.util.List.of("id", "email"),
                QuerySpec.empty().where(Criteria.eq("email", "a@nova.io"))
        );

        assertEquals(
                "select id as id, email_address as email_address from soft_deletable_accounts where email_address = ? and deleted_at is null",
                statement.sql()
        );
        assertEquals(java.util.List.of("a@nova.io"), statement.bindings());
    }

    @Test
    void projectionSelectAppendsSoftDeleteAliveAsSoleWhenNoPredicate() {
        SqlStatement statement = dialect.sqlRenderer().selectProjection(
                softMetadata,
                java.util.List.of("id"),
                QuerySpec.empty()
        );

        assertEquals(
                "select id as id from soft_deletable_accounts where deleted_at is null",
                statement.sql()
        );
        assertEquals(java.util.List.of(), statement.bindings());
    }

    @Test
    void projectionSelectRejectsEmptyFields() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> dialect.sqlRenderer().selectProjection(metadata, java.util.List.of(), QuerySpec.empty())
        );

        assertEquals("selectProjection requires at least one field", exception.getMessage());
    }

    @Test
    void projectionSelectRejectsUnknownField() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> dialect.sqlRenderer().selectProjection(metadata, java.util.List.of("missing"), QuerySpec.empty())
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
