package io.nova.dialect.h2;

import io.nova.metadata.DefaultNamingStrategy;
import io.nova.metadata.EntityMetadata;
import io.nova.metadata.EntityMetadataFactory;
import io.nova.query.Criteria;
import io.nova.query.Pageable;
import io.nova.query.QuerySpec;
import io.nova.query.Sort;
import io.nova.sql.SqlStatement;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class H2SqlRendererTest {
    private final H2Dialect dialect = new H2Dialect();
    private final EntityMetadata<H2SampleAccount> metadata = new EntityMetadataFactory(new DefaultNamingStrategy())
            .getEntityMetadata(H2SampleAccount.class);

    @Test
    void rendersSelectByIdWithDoubleQuotedIdentifiersAndQuestionMark() {
        SqlStatement statement = dialect.sqlRenderer().selectById(metadata, 42L);

        assertEquals(
                "select \"id\" as \"id\", \"email_address\" as \"email_address\", \"active\" as \"active\" from \"accounts\" where \"id\" = ?",
                statement.sql()
        );
        assertEquals(List.of(42L), statement.bindings());
    }

    @Test
    void rendersPagedSelectWithQuestionMarkBindMarkers() {
        SqlStatement statement = dialect.sqlRenderer().select(
                metadata,
                QuerySpec.empty()
                        .where(Criteria.eq("email", "a@nova.io"))
                        .orderBy(Sort.by(Sort.Order.desc("id")))
                        .page(Pageable.of(5, 10))
        );

        assertEquals(
                "select \"id\" as \"id\", \"email_address\" as \"email_address\", \"active\" as \"active\" from \"accounts\" where \"email_address\" = ? order by \"id\" desc limit ? offset ?",
                statement.sql()
        );
        assertEquals(List.of("a@nova.io", 5, 10L), statement.bindings());
    }

    @Test
    void rendersInsertWithQuestionMarkMarkersWithoutReturningClauseForIdentityId() {
        // H2 2.1.214는 INSERT...RETURNING을 지원하지 않으므로 dialect는 RETURNING 절을 붙이지 않는다.
        // 생성된 IDENTITY 키는 R2DBC Statement.returnGeneratedValues(...) 경로로 회수된다.
        SqlStatement statement = dialect.sqlRenderer().insert(
                metadata,
                new H2SampleAccount("h2@nova.io", true)
        );

        assertEquals(
                "insert into \"accounts\" (\"email_address\", \"active\") values (?, ?)",
                statement.sql()
        );
        assertEquals(List.of("h2@nova.io", true), statement.bindings());
    }

    @Test
    void rendersInsertForAssignedIdWithoutReturningClause() {
        EntityMetadata<H2AssignedIdAccount> assigned = new EntityMetadataFactory(new DefaultNamingStrategy())
                .getEntityMetadata(H2AssignedIdAccount.class);

        SqlStatement statement = dialect.sqlRenderer().insert(
                assigned,
                new H2AssignedIdAccount(7L, "assigned@nova.io")
        );

        assertEquals(
                "insert into \"assigned_accounts\" (\"id\", \"email_address\") values (?, ?)",
                statement.sql()
        );
        assertEquals(List.of(7L, "assigned@nova.io"), statement.bindings());
    }

    @Test
    void rendersUpdateWithQuestionMarkMarkers() {
        EntityMetadata<H2AssignedIdAccount> assigned = new EntityMetadataFactory(new DefaultNamingStrategy())
                .getEntityMetadata(H2AssignedIdAccount.class);
        SqlStatement statement = dialect.sqlRenderer().update(
                assigned,
                new H2AssignedIdAccount(4L, "h2@nova.io")
        );

        assertEquals(
                "update \"assigned_accounts\" set \"email_address\" = ? where \"id\" = ?",
                statement.sql()
        );
        assertEquals(List.of("h2@nova.io", 4L), statement.bindings());
    }

    @Test
    void rendersDeleteById() {
        SqlStatement statement = dialect.sqlRenderer().deleteById(metadata, 9L);

        assertEquals("delete from \"accounts\" where \"id\" = ?", statement.sql());
        assertEquals(List.of(9L), statement.bindings());
    }

    @Test
    void rendersExistsQuery() {
        SqlStatement statement = dialect.sqlRenderer().exists(
                metadata,
                QuerySpec.empty().where(Criteria.isNotNull("email"))
        );

        assertEquals("select 1 from \"accounts\" where \"email_address\" is not null limit 1", statement.sql());
    }

    @Test
    void rendersIlikeUsingLowerBasedFallback() {
        SqlStatement statement = dialect.sqlRenderer().select(
                metadata,
                QuerySpec.empty().where(Criteria.ilike("email", "%NOVA%"))
        );

        assertEquals(
                "select \"id\" as \"id\", \"email_address\" as \"email_address\", \"active\" as \"active\" "
                        + "from \"accounts\" where lower(\"email_address\") like lower(?)",
                statement.sql()
        );
        assertEquals(List.of("%NOVA%"), statement.bindings());
    }

    @Test
    void rendersNotIlikeUsingLowerBasedFallback() {
        SqlStatement statement = dialect.sqlRenderer().select(
                metadata,
                QuerySpec.empty().where(Criteria.notIlike("email", "noreply%"))
        );

        assertEquals(
                "select \"id\" as \"id\", \"email_address\" as \"email_address\", \"active\" as \"active\" "
                        + "from \"accounts\" where lower(\"email_address\") not like lower(?)",
                statement.sql()
        );
        assertEquals(List.of("noreply%"), statement.bindings());
    }

    @Test
    void rendersContainsAsLikeSurroundedByWildcards() {
        SqlStatement statement = dialect.sqlRenderer().select(
                metadata,
                QuerySpec.empty().where(Criteria.contains("email", "nova"))
        );

        assertEquals(
                "select \"id\" as \"id\", \"email_address\" as \"email_address\", \"active\" as \"active\" "
                        + "from \"accounts\" where \"email_address\" like ?",
                statement.sql()
        );
        assertEquals(List.of("%nova%"), statement.bindings());
    }

    @Test
    void rendersSelectWithForUpdateLockClauseAfterPaging() {
        SqlStatement statement = dialect.sqlRenderer().select(
                metadata,
                QuerySpec.empty()
                        .where(Criteria.eq("email", "a@nova.io"))
                        .page(Pageable.of(5, 10))
                        .forUpdate()
        );

        assertEquals(
                "select \"id\" as \"id\", \"email_address\" as \"email_address\", \"active\" as \"active\" "
                        + "from \"accounts\" where \"email_address\" = ? limit ? offset ? for update",
                statement.sql()
        );
        assertEquals(List.of("a@nova.io", 5, 10L), statement.bindings());
    }
}
