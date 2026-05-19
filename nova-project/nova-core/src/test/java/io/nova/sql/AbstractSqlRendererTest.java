package io.nova.sql;

import io.nova.metadata.DefaultNamingStrategy;
import io.nova.metadata.EntityMetadata;
import io.nova.metadata.EntityMetadataFactory;
import io.nova.query.AggregateSpec;
import io.nova.query.Aggregation;
import io.nova.query.Criteria;
import io.nova.query.Cursor;
import io.nova.query.CursorField;
import io.nova.query.LockMode;
import io.nova.query.Pageable;
import io.nova.query.QuerySpec;
import io.nova.query.Sort;
import io.nova.support.fixtures.FixtureEntities.Address;
import io.nova.support.fixtures.FixtureEntities.Customer;
import io.nova.support.fixtures.FixtureEntities.IntegerVersionedAccount;
import io.nova.support.fixtures.FixtureEntities.SampleAccount;
import io.nova.support.fixtures.FixtureEntities.ShortVersionedAccount;
import io.nova.support.fixtures.FixtureEntities.SoftDeletableAccount;
import io.nova.support.fixtures.FixtureEntities.VersionedAccount;
import io.nova.support.fixtures.FixtureEntities.VersionedSoftDeletableAccount;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void rendersLikeOperator() {
        SqlStatement statement = dialect.sqlRenderer().select(
                metadata,
                QuerySpec.empty().where(Criteria.like("email", "%nova.io"))
        );

        assertEquals(
                "select id as id, email_address as email_address, active as active from accounts where email_address like ?",
                statement.sql()
        );
        assertEquals(java.util.List.of("%nova.io"), statement.bindings());
    }

    @Test
    void rendersNotLikeOperator() {
        SqlStatement statement = dialect.sqlRenderer().select(
                metadata,
                QuerySpec.empty().where(Criteria.notLike("email", "%nova.io"))
        );

        assertEquals(
                "select id as id, email_address as email_address, active as active from accounts where email_address not like ?",
                statement.sql()
        );
        assertEquals(java.util.List.of("%nova.io"), statement.bindings());
    }

    @Test
    void rendersIlikeWithDefaultLowerBasedFallback() {
        SqlStatement statement = dialect.sqlRenderer().select(
                metadata,
                QuerySpec.empty().where(Criteria.ilike("email", "%NOVA%"))
        );

        assertEquals(
                "select id as id, email_address as email_address, active as active from accounts where lower(email_address) like lower(?)",
                statement.sql()
        );
        assertEquals(java.util.List.of("%NOVA%"), statement.bindings());
    }

    @Test
    void rendersNotIlikeWithDefaultLowerBasedFallback() {
        SqlStatement statement = dialect.sqlRenderer().select(
                metadata,
                QuerySpec.empty().where(Criteria.notIlike("email", "noreply%"))
        );

        assertEquals(
                "select id as id, email_address as email_address, active as active from accounts where lower(email_address) not like lower(?)",
                statement.sql()
        );
        assertEquals(java.util.List.of("noreply%"), statement.bindings());
    }

    @Test
    void rendersStartsWithAsLikePrefixPattern() {
        SqlStatement statement = dialect.sqlRenderer().select(
                metadata,
                QuerySpec.empty().where(Criteria.startsWith("email", "ada"))
        );

        assertEquals(
                "select id as id, email_address as email_address, active as active from accounts where email_address like ?",
                statement.sql()
        );
        assertEquals(java.util.List.of("ada%"), statement.bindings());
    }

    @Test
    void rendersEndsWithAsLikeSuffixPattern() {
        SqlStatement statement = dialect.sqlRenderer().select(
                metadata,
                QuerySpec.empty().where(Criteria.endsWith("email", "@nova.io"))
        );

        assertEquals(
                "select id as id, email_address as email_address, active as active from accounts where email_address like ?",
                statement.sql()
        );
        assertEquals(java.util.List.of("%@nova.io"), statement.bindings());
    }

    @Test
    void rendersContainsAsLikeSurroundedByWildcards() {
        SqlStatement statement = dialect.sqlRenderer().select(
                metadata,
                QuerySpec.empty().where(Criteria.contains("email", "nova"))
        );

        assertEquals(
                "select id as id, email_address as email_address, active as active from accounts where email_address like ?",
                statement.sql()
        );
        assertEquals(java.util.List.of("%nova%"), statement.bindings());
    }

    @Test
    void rendersContainsIgnoreCaseAsIlikeSurroundedByWildcards() {
        SqlStatement statement = dialect.sqlRenderer().select(
                metadata,
                QuerySpec.empty().where(Criteria.containsIgnoreCase("email", "NoVa"))
        );

        assertEquals(
                "select id as id, email_address as email_address, active as active from accounts where lower(email_address) like lower(?)",
                statement.sql()
        );
        assertEquals(java.util.List.of("%NoVa%"), statement.bindings());
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
    void softDeleteByQueryRendersUpdateWithPredicateAndAliveGuard() {
        Instant now = Instant.parse("2026-05-18T10:00:00Z");

        SqlStatement statement = dialect.sqlRenderer().softDeleteByQuery(
                softMetadata,
                QuerySpec.empty().where(Criteria.eq("email", "a@nova.io")),
                now
        );

        assertEquals(
                "update soft_deletable_accounts set deleted_at = ? where email_address = ? and deleted_at is null",
                statement.sql()
        );
        assertEquals(java.util.List.of(now, "a@nova.io"), statement.bindings());
    }

    @Test
    void softDeleteByQueryRejectsNullPredicate() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> dialect.sqlRenderer().softDeleteByQuery(softMetadata, QuerySpec.empty(), Instant.EPOCH)
        );

        assertEquals("softDeleteByQuery requires a non-null predicate", exception.getMessage());
    }

    @Test
    void softDeleteByQueryRejectsSort() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> dialect.sqlRenderer().softDeleteByQuery(
                        softMetadata,
                        QuerySpec.empty().where(Criteria.eq("email", "a")).orderBy(Sort.by(Sort.Order.asc("id"))),
                        Instant.EPOCH
                )
        );

        assertEquals("softDeleteByQuery does not support sort", exception.getMessage());
    }

    @Test
    void softDeleteByQueryRejectsPageable() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> dialect.sqlRenderer().softDeleteByQuery(
                        softMetadata,
                        QuerySpec.empty().where(Criteria.eq("email", "a")).page(Pageable.of(10, 0)),
                        Instant.EPOCH
                )
        );

        assertEquals("softDeleteByQuery does not support pageable", exception.getMessage());
    }

    @Test
    void softDeleteByQueryRejectsMetadataWithoutSoftDelete() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> dialect.sqlRenderer().softDeleteByQuery(
                        metadata,
                        QuerySpec.empty().where(Criteria.eq("email", "a")),
                        Instant.EPOCH
                )
        );

        assertEquals(
                "softDeleteByQuery requires @SoftDelete on " + SampleAccount.class.getName(),
                exception.getMessage()
        );
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
    void rendersPartialUpdateWithVersionIncrementAndWhereCheck() {
        EntityMetadata<VersionedAccount> versionedMetadata = new EntityMetadataFactory(new DefaultNamingStrategy())
                .getEntityMetadata(VersionedAccount.class);

        SqlStatement statement = dialect.sqlRenderer().update(
                versionedMetadata,
                new VersionedAccount(7L, "a@nova.io", 4L),
                java.util.List.of("email", "version")
        );

        assertEquals(
                "update versioned_accounts set email_address = ?, version = ? where id = ? and version = ?",
                statement.sql()
        );
        assertEquals(java.util.List.of("a@nova.io", 5L, 7L, 4L), statement.bindings());
    }

    @Test
    void partialUpdateWithoutVersionFieldStillAddsVersionWhereCheckForVersionedEntity() {
        EntityMetadata<VersionedAccount> versionedMetadata = new EntityMetadataFactory(new DefaultNamingStrategy())
                .getEntityMetadata(VersionedAccount.class);

        SqlStatement statement = dialect.sqlRenderer().update(
                versionedMetadata,
                new VersionedAccount(7L, "a@nova.io", 4L),
                java.util.List.of("email")
        );

        assertEquals(
                "update versioned_accounts set email_address = ? where id = ? and version = ?",
                statement.sql()
        );
        assertEquals(java.util.List.of("a@nova.io", 7L, 4L), statement.bindings());
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
    void softDeleteByEntityCombinesSoftDeleteSetWithVersionIncrementAndCheck() {
        EntityMetadata<VersionedSoftDeletableAccount> versionedSoft =
                new EntityMetadataFactory(new DefaultNamingStrategy())
                        .getEntityMetadata(VersionedSoftDeletableAccount.class);
        Instant now = Instant.parse("2026-05-18T10:00:00Z");

        SqlStatement statement = dialect.sqlRenderer().softDeleteByEntity(
                versionedSoft,
                new VersionedSoftDeletableAccount(7L, "a@nova.io", 4L, null),
                now
        );

        assertEquals(
                "update versioned_soft_deletable_accounts set deleted_at = ?, version = ? where id = ? and version = ? and deleted_at is null",
                statement.sql()
        );
        assertEquals(java.util.List.of(now, 5L, 7L, 4L), statement.bindings());
    }

    @Test
    void softDeleteByEntityWithoutVersionFallsBackToSoftDeleteById() {
        Instant now = Instant.parse("2026-05-18T10:00:00Z");

        SqlStatement statement = dialect.sqlRenderer().softDeleteByEntity(
                softMetadata,
                new SoftDeletableAccount(7L, "a@nova.io", null),
                now
        );

        assertEquals(
                "update soft_deletable_accounts set deleted_at = ? where id = ? and deleted_at is null",
                statement.sql()
        );
        assertEquals(java.util.List.of(now, 7L), statement.bindings());
    }

    @Test
    void softDeleteByEntityRejectsMetadataWithoutSoftDelete() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> dialect.sqlRenderer().softDeleteByEntity(
                        metadata,
                        new SampleAccount(7L, "a@nova.io", true),
                        Instant.EPOCH
                )
        );

        assertEquals(
                "softDeleteByEntity requires @SoftDelete on " + SampleAccount.class.getName(),
                exception.getMessage()
        );
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
    void aggregateRendersSingleCountWithDefaultAlias() {
        SqlStatement statement = dialect.sqlRenderer().aggregate(
                metadata,
                AggregateSpec.of(Aggregation.count("id"))
        );

        assertEquals("select count(id) as count from accounts", statement.sql());
        assertEquals(java.util.List.of(), statement.bindings());
    }

    @Test
    void aggregateRendersMultipleFunctionsWithExplicitAliases() {
        SqlStatement statement = dialect.sqlRenderer().aggregate(
                metadata,
                AggregateSpec.of(
                        Aggregation.countDistinct("email").as("unique_emails"),
                        Aggregation.sum("active").as("active_sum")
                )
        );

        assertEquals(
                "select count(distinct email_address) as unique_emails, sum(active) as active_sum from accounts",
                statement.sql()
        );
        assertEquals(java.util.List.of(), statement.bindings());
    }

    @Test
    void aggregateRendersGroupByAndPlacesGroupColumnsInSelect() {
        SqlStatement statement = dialect.sqlRenderer().aggregate(
                metadata,
                AggregateSpec.of(Aggregation.count("id").as("c")).groupBy("active")
        );

        assertEquals(
                "select active as active, count(id) as c from accounts group by active",
                statement.sql()
        );
        assertEquals(java.util.List.of(), statement.bindings());
    }

    @Test
    void aggregateRendersWhereAndGroupByInExpectedOrder() {
        SqlStatement statement = dialect.sqlRenderer().aggregate(
                metadata,
                AggregateSpec.of(Aggregation.count("id").as("c"))
                        .where(Criteria.eq("active", true))
                        .groupBy("email")
        );

        assertEquals(
                "select email_address as email_address, count(id) as c from accounts where active = ? group by email_address",
                statement.sql()
        );
        assertEquals(java.util.List.of(true), statement.bindings());
    }

    @Test
    void aggregateRendersHavingOnAggregateAlias() {
        SqlStatement statement = dialect.sqlRenderer().aggregate(
                metadata,
                AggregateSpec.of(Aggregation.count("id").as("c"))
                        .groupBy("active")
                        .having(Criteria.gt("c", 5L))
        );

        assertEquals(
                "select active as active, count(id) as c from accounts group by active having count(id) > ?",
                statement.sql()
        );
        assertEquals(java.util.List.of(5L), statement.bindings());
    }

    @Test
    void aggregateHavingFallsBackToEntityPropertyWhenAliasNotMatched() {
        SqlStatement statement = dialect.sqlRenderer().aggregate(
                metadata,
                AggregateSpec.of(Aggregation.count("id").as("c"))
                        .groupBy("active")
                        .having(Criteria.eq("email", "x@nova.io"))
        );

        assertEquals(
                "select active as active, count(id) as c from accounts group by active having email_address = ?",
                statement.sql()
        );
        assertEquals(java.util.List.of("x@nova.io"), statement.bindings());
    }

    @Test
    void aggregateRendersOrderByAggregateAliasAndGroupProperty() {
        SqlStatement statement = dialect.sqlRenderer().aggregate(
                metadata,
                AggregateSpec.of(Aggregation.count("id").as("c"))
                        .groupBy("active")
                        .orderBy(Sort.by(Sort.Order.desc("c"), Sort.Order.asc("active")))
        );

        assertEquals(
                "select active as active, count(id) as c from accounts group by active order by count(id) desc, active asc",
                statement.sql()
        );
    }

    @Test
    void aggregateAppendsSoftDeleteAliveGuardInWhere() {
        SqlStatement statement = dialect.sqlRenderer().aggregate(
                softMetadata,
                AggregateSpec.of(Aggregation.count("id"))
        );

        assertEquals(
                "select count(id) as count from soft_deletable_accounts where deleted_at is null",
                statement.sql()
        );
    }

    @Test
    void aggregateRendersAllSixFunctionFormsCorrectly() {
        SqlStatement statement = dialect.sqlRenderer().aggregate(
                metadata,
                AggregateSpec.of(
                        Aggregation.count("id"),
                        Aggregation.countDistinct("email").as("c2"),
                        Aggregation.sum("id").as("s"),
                        Aggregation.avg("id").as("a"),
                        Aggregation.min("id").as("mn"),
                        Aggregation.max("id").as("mx")
                )
        );

        assertEquals(
                "select count(id) as count, count(distinct email_address) as c2, sum(id) as s, avg(id) as a, min(id) as mn, max(id) as mx from accounts",
                statement.sql()
        );
    }

    @Test
    void aggregateRejectsUnknownPropertyInAggregation() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> dialect.sqlRenderer().aggregate(metadata, AggregateSpec.of(Aggregation.sum("missing")))
        );

        assertEquals("Unknown property missing on " + SampleAccount.class.getName(), exception.getMessage());
    }

    @Test
    void aggregateRejectsUnknownPropertyInGroupBy() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> dialect.sqlRenderer().aggregate(
                        metadata,
                        AggregateSpec.of(Aggregation.count("id")).groupBy("missing")
                )
        );

        assertEquals("Unknown property missing on " + SampleAccount.class.getName(), exception.getMessage());
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

    @Test
    void rendersSingleAscCursorAsGreaterThanWithLimitAndNoOffset() {
        SqlStatement statement = dialect.sqlRenderer().select(
                metadata,
                QuerySpec.empty()
                        .orderBy(Sort.by(Sort.Order.asc("id")))
                        .cursor(Cursor.of(CursorField.asc("id", 42L)), 10)
        );

        assertEquals(
                "select id as id, email_address as email_address, active as active from accounts where ((id > ?)) order by id asc limit ?",
                statement.sql()
        );
        assertEquals(java.util.List.of(42L, 10), statement.bindings());
    }

    @Test
    void rendersSingleDescCursorAsLessThan() {
        SqlStatement statement = dialect.sqlRenderer().select(
                metadata,
                QuerySpec.empty()
                        .orderBy(Sort.by(Sort.Order.desc("id")))
                        .cursor(Cursor.of(CursorField.desc("id", 100L)), 5)
        );

        assertEquals(
                "select id as id, email_address as email_address, active as active from accounts where ((id < ?)) order by id desc limit ?",
                statement.sql()
        );
        assertEquals(java.util.List.of(100L, 5), statement.bindings());
    }

    @Test
    void rendersMultiFieldCursorAsLexicographicComparison() {
        SqlStatement statement = dialect.sqlRenderer().select(
                metadata,
                QuerySpec.empty()
                        .orderBy(Sort.by(Sort.Order.desc("email"), Sort.Order.asc("id")))
                        .cursor(
                                Cursor.of(
                                        CursorField.desc("email", "m@nova.io"),
                                        CursorField.asc("id", 50L)
                                ),
                                20
                        )
        );

        assertEquals(
                "select id as id, email_address as email_address, active as active from accounts" +
                        " where ((email_address < ?) or (email_address = ? and id > ?))" +
                        " order by email_address desc, id asc limit ?",
                statement.sql()
        );
        assertEquals(java.util.List.of("m@nova.io", "m@nova.io", 50L, 20), statement.bindings());
    }

    @Test
    void cursorPredicateCombinesWithUserPredicateViaAnd() {
        SqlStatement statement = dialect.sqlRenderer().select(
                metadata,
                QuerySpec.empty()
                        .where(Criteria.eq("active", true))
                        .orderBy(Sort.by(Sort.Order.asc("id")))
                        .cursor(Cursor.of(CursorField.asc("id", 7L)), 25)
        );

        assertEquals(
                "select id as id, email_address as email_address, active as active from accounts" +
                        " where active = ? and ((id > ?))" +
                        " order by id asc limit ?",
                statement.sql()
        );
        assertEquals(java.util.List.of(true, 7L, 25), statement.bindings());
    }

    @Test
    void cursorPredicateAppendsBeforeSoftDeleteAliveGuard() {
        SqlStatement statement = dialect.sqlRenderer().select(
                softMetadata,
                QuerySpec.empty()
                        .orderBy(Sort.by(Sort.Order.asc("id")))
                        .cursor(Cursor.of(CursorField.asc("id", 3L)), 5)
        );

        assertEquals(
                "select id as id, email_address as email_address, deleted_at as deleted_at" +
                        " from soft_deletable_accounts where ((id > ?)) and deleted_at is null" +
                        " order by id asc limit ?",
                statement.sql()
        );
        assertEquals(java.util.List.of(3L, 5), statement.bindings());
    }

    @Test
    void cursorSelectIgnoresOffsetEvenIfPageableCarriesOne() {
        SqlStatement statement = dialect.sqlRenderer().select(
                metadata,
                QuerySpec.empty()
                        .orderBy(Sort.by(Sort.Order.asc("id")))
                        .page(Pageable.of(10, 100))
                        .cursor(Cursor.of(CursorField.asc("id", 9L)), 10)
        );

        assertEquals(
                "select id as id, email_address as email_address, active as active from accounts where ((id > ?)) order by id asc limit ?",
                statement.sql()
        );
        assertEquals(java.util.List.of(9L, 10), statement.bindings());
    }

    @Test
    void cursorAppliesToProjectionSelect() {
        SqlStatement statement = dialect.sqlRenderer().selectProjection(
                metadata,
                java.util.List.of("id", "email"),
                QuerySpec.empty()
                        .orderBy(Sort.by(Sort.Order.asc("id")))
                        .cursor(Cursor.of(CursorField.asc("id", 4L)), 15)
        );

        assertEquals(
                "select id as id, email_address as email_address from accounts where ((id > ?)) order by id asc limit ?",
                statement.sql()
        );
        assertEquals(java.util.List.of(4L, 15), statement.bindings());
    }

    @Test
    void cursorPreservesExistingPageableLimitWhenLimitOmitted() {
        SqlStatement statement = dialect.sqlRenderer().select(
                metadata,
                QuerySpec.empty()
                        .orderBy(Sort.by(Sort.Order.asc("id")))
                        .page(Pageable.of(7, 250))
                        .cursor(Cursor.of(CursorField.asc("id", 11L)))
        );

        assertEquals(
                "select id as id, email_address as email_address, active as active from accounts where ((id > ?)) order by id asc limit ?",
                statement.sql()
        );
        assertEquals(java.util.List.of(11L, 7), statement.bindings(),
                "cursor(Cursor) overload는 기존 pageable의 limit을 보존해야 한다");
    }

    @Test
    void cursorWithoutLimitRejectsMissingPageable() {
        QuerySpec spec = QuerySpec.empty()
                .orderBy(Sort.by(Sort.Order.asc("id")));

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> spec.cursor(Cursor.of(CursorField.asc("id", 1L)))
        );

        assertTrue(exception.getMessage().contains("requires an existing pageable"),
                "메시지: " + exception.getMessage());
    }

    @Test
    void cursorRejectsUnknownProperty() {
        QuerySpec spec = QuerySpec.empty()
                .orderBy(Sort.by(Sort.Order.asc("id")))
                .cursor(Cursor.of(CursorField.asc("notAProperty", 1L)), 10);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> dialect.sqlRenderer().select(metadata, spec)
        );

        assertEquals(
                "Unknown property notAProperty on " + SampleAccount.class.getName(),
                exception.getMessage()
        );
    }

    @Test
    void deleteByQueryRejectsCursor() {
        QuerySpec spec = QuerySpec.empty()
                .where(Criteria.eq("active", false))
                .cursor(Cursor.of(CursorField.asc("id", 1L)), 10);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> dialect.sqlRenderer().deleteByQuery(metadata, spec)
        );

        assertEquals("deleteByQuery does not support cursor", exception.getMessage());
    }

    @Test
    void updateByQueryRejectsCursor() {
        java.util.LinkedHashMap<String, Object> fields = new java.util.LinkedHashMap<>();
        fields.put("email", "x@nova.io");

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> dialect.sqlRenderer().updateByQuery(
                        metadata,
                        fields,
                        QuerySpec.empty()
                                .where(Criteria.eq("id", 1L))
                                .cursor(Cursor.of(CursorField.asc("id", 1L)), 10)
                )
        );

        assertEquals("updateByQuery does not support cursor", exception.getMessage());
    }

    @Test
    void rendersInsertForCustomerWithEmbeddedAddress() {
        EntityMetadata<Customer> customerMetadata = metadataFactory.getEntityMetadata(Customer.class);
        Customer customer = new Customer(7L, "Ada", new Address("Seoul", "Gangnam-daero", "06000"));

        SqlStatement statement = dialect.sqlRenderer().insert(customerMetadata, customer);

        assertEquals(
                "insert into customer (id, name, shipping_city, shipping_street, shipping_zip) values (?, ?, ?, ?, ?)",
                statement.sql()
        );
        assertEquals(java.util.List.of(7L, "Ada", "Seoul", "Gangnam-daero", "06000"), statement.bindings());
    }

    @Test
    void rendersInsertForCustomerWithNullEmbeddedAddress() {
        EntityMetadata<Customer> customerMetadata = metadataFactory.getEntityMetadata(Customer.class);
        Customer customer = new Customer(7L, "Ada", null);

        SqlStatement statement = dialect.sqlRenderer().insert(customerMetadata, customer);

        assertEquals(
                "insert into customer (id, name, shipping_city, shipping_street, shipping_zip) values (?, ?, ?, ?, ?)",
                statement.sql()
        );
        assertEquals(java.util.Arrays.asList(7L, "Ada", null, null, null), statement.bindings());
    }

    @Test
    void rendersSelectForCustomerWithEmbeddedAddressColumns() {
        EntityMetadata<Customer> customerMetadata = metadataFactory.getEntityMetadata(Customer.class);

        SqlStatement statement = dialect.sqlRenderer().select(customerMetadata, QuerySpec.empty());

        assertEquals(
                "select id as id, name as name, shipping_city as shipping_city, "
                        + "shipping_street as shipping_street, shipping_zip as shipping_zip from customer",
                statement.sql()
        );
        assertEquals(java.util.List.of(), statement.bindings());
    }

    @Test
    void selectAppendsForUpdateClauseWhenLockModeIsForUpdate() {
        SqlStatement statement = dialect.sqlRenderer().select(
                metadata,
                QuerySpec.empty().forUpdate()
        );

        assertEquals(
                "select id as id, email_address as email_address, active as active from accounts for update",
                statement.sql()
        );
        assertEquals(java.util.List.of(), statement.bindings());
    }

    @Test
    void selectAppendsForShareClauseWhenLockModeIsForShare() {
        SqlStatement statement = dialect.sqlRenderer().select(
                metadata,
                QuerySpec.empty().forShare()
        );

        assertEquals(
                "select id as id, email_address as email_address, active as active from accounts for share",
                statement.sql()
        );
    }

    @Test
    void selectOmitsLockClauseForLockModeNone() {
        SqlStatement statement = dialect.sqlRenderer().select(
                metadata,
                QuerySpec.empty().lockMode(LockMode.NONE)
        );

        assertEquals(
                "select id as id, email_address as email_address, active as active from accounts",
                statement.sql()
        );
    }

    @Test
    void selectAppendsLockClauseAfterWhereOrderByAndPaging() {
        SqlStatement statement = dialect.sqlRenderer().select(
                metadata,
                QuerySpec.empty()
                        .where(Criteria.eq("email", "a@nova.io"))
                        .orderBy(Sort.by(Sort.Order.asc("id")))
                        .page(Pageable.of(10, 20))
                        .forUpdate()
        );

        assertEquals(
                "select id as id, email_address as email_address, active as active from accounts "
                        + "where email_address = ? order by id asc limit ? offset ? for update",
                statement.sql()
        );
        assertEquals(java.util.List.of("a@nova.io", 10, 20L), statement.bindings());
    }

    @Test
    void countDoesNotAppendLockClauseEvenWhenLockModeRequested() {
        SqlStatement statement = dialect.sqlRenderer().count(
                metadata,
                QuerySpec.empty().forUpdate()
        );

        assertEquals("select count(*) as count from accounts", statement.sql());
    }

    @Test
    void existsDoesNotAppendLockClauseEvenWhenLockModeRequested() {
        SqlStatement statement = dialect.sqlRenderer().exists(
                metadata,
                QuerySpec.empty().forUpdate()
        );

        assertEquals("select 1 from accounts limit 1", statement.sql());
    }

    @Test
    void selectByIdDoesNotAppendLockClause() {
        SqlStatement statement = dialect.sqlRenderer().selectById(metadata, 7L);

        assertEquals(
                "select id as id, email_address as email_address, active as active from accounts where id = ?",
                statement.sql()
        );
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
