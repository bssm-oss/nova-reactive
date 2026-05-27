package io.nova.dialect.oracle;

import io.nova.metadata.DefaultNamingStrategy;
import io.nova.metadata.EntityMetadata;
import io.nova.metadata.EntityMetadataFactory;
import io.nova.query.Criteria;
import io.nova.query.Cursor;
import io.nova.query.CursorField;
import io.nova.query.Pageable;
import io.nova.query.QuerySpec;
import io.nova.query.Sort;
import io.nova.sql.SqlStatement;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OracleSqlRendererTest {
    private final OracleDialect dialect = new OracleDialect();
    private final EntityMetadata<OracleSampleAccount> metadata = new EntityMetadataFactory(new DefaultNamingStrategy())
            .getEntityMetadata(OracleSampleAccount.class);

    @Test
    void rendersSelectByIdWithDoubleQuotedIdentifiersAndQuestionMark() {
        SqlStatement statement = dialect.sqlRenderer().selectById(metadata, 42L);

        assertEquals(
                "select \"id\" as \"id\", \"email_address\" as \"email_address\", \"active\" as \"active\" "
                        + "from \"accounts\" where \"id\" = ?",
                statement.sql()
        );
        assertEquals(List.of(42L), statement.bindings());
    }

    @Test
    void rendersPagedSelectUsingOffsetRowsFetchNextRowsOnly() {
        SqlStatement statement = dialect.sqlRenderer().select(
                metadata,
                QuerySpec.empty()
                        .where(Criteria.eq("email", "a@nova.io"))
                        .orderBy(Sort.by(Sort.Order.desc("id")))
                        .page(Pageable.of(5, 10))
        );

        assertEquals(
                "select \"id\" as \"id\", \"email_address\" as \"email_address\", \"active\" as \"active\" "
                        + "from \"accounts\" where \"email_address\" = ? order by \"id\" desc "
                        + "offset ? rows fetch next ? rows only",
                statement.sql()
        );
        // Oracle은 offset을 먼저 바인딩한다 — marker 순서(offset, limit)와 binding 순서가 일치한다.
        assertEquals(List.of("a@nova.io", 10L, 5), statement.bindings());
    }

    @Test
    void rendersPagedSelectWithoutPredicateUsingOffsetFetch() {
        SqlStatement statement = dialect.sqlRenderer().select(
                metadata,
                QuerySpec.empty()
                        .orderBy(Sort.by(Sort.Order.asc("id")))
                        .page(Pageable.of(20, 0))
        );

        assertEquals(
                "select \"id\" as \"id\", \"email_address\" as \"email_address\", \"active\" as \"active\" "
                        + "from \"accounts\" order by \"id\" asc offset ? rows fetch next ? rows only",
                statement.sql()
        );
        assertEquals(List.of(0L, 20), statement.bindings());
    }

    @Test
    void rendersKeysetCursorPaginationUsingFetchFirstRowsOnlyWithoutOffset() {
        SqlStatement statement = dialect.sqlRenderer().select(
                metadata,
                QuerySpec.empty()
                        .orderBy(Sort.by(Sort.Order.desc("id")))
                        .cursor(Cursor.of(CursorField.desc("id", 100L)), 5)
        );

        assertEquals(
                "select \"id\" as \"id\", \"email_address\" as \"email_address\", \"active\" as \"active\" "
                        + "from \"accounts\" where ((\"id\" < ?)) order by \"id\" desc fetch first ? rows only",
                statement.sql()
        );
        // cursor 비교 값(100L) 바인딩 후 fetch-first limit(5)만 추가된다 — offset은 생략된다.
        assertEquals(List.of(100L, 5), statement.bindings());
    }

    @Test
    void selectWithoutPageableOmitsFetchClause() {
        SqlStatement statement = dialect.sqlRenderer().select(
                metadata,
                QuerySpec.empty().where(Criteria.eq("email", "a@nova.io"))
        );

        assertEquals(
                "select \"id\" as \"id\", \"email_address\" as \"email_address\", \"active\" as \"active\" "
                        + "from \"accounts\" where \"email_address\" = ?",
                statement.sql()
        );
        assertEquals(List.of("a@nova.io"), statement.bindings());
    }

    @Test
    void appendsForUpdateLockClauseAfterFetchClause() {
        SqlStatement statement = dialect.sqlRenderer().select(
                metadata,
                QuerySpec.empty()
                        .where(Criteria.eq("email", "a@nova.io"))
                        .page(Pageable.of(5, 10))
                        .forUpdate()
        );

        assertEquals(
                "select \"id\" as \"id\", \"email_address\" as \"email_address\", \"active\" as \"active\" "
                        + "from \"accounts\" where \"email_address\" = ? offset ? rows fetch next ? rows only for update",
                statement.sql()
        );
        assertEquals(List.of("a@nova.io", 10L, 5), statement.bindings());
    }

    @Test
    void rendersUpdateWithQuestionMarkMarkers() {
        SqlStatement statement = dialect.sqlRenderer().update(
                metadata,
                new OracleSampleAccount(4L, "oracle@nova.io", false)
        );

        assertEquals(
                "update \"accounts\" set \"email_address\" = ?, \"active\" = ? where \"id\" = ?",
                statement.sql()
        );
        assertEquals(List.of("oracle@nova.io", false, 4L), statement.bindings());
    }

    @Test
    void rendersExistsQueryUsingFetchFirstRowsOnlyNotLimit() {
        // Oracle은 LIMIT을 지원하지 않으므로(ORA-00933) exists()의 single-row 가드는
        // 12c+ 표준 FETCH FIRST 1 ROWS ONLY로 렌더돼야 한다.
        SqlStatement statement = dialect.sqlRenderer().exists(
                metadata,
                QuerySpec.empty().where(Criteria.isNotNull("email"))
        );

        assertEquals(
                "select 1 from \"accounts\" where \"email_address\" is not null fetch first 1 rows only",
                statement.sql()
        );
        assertEquals(List.of(), statement.bindings());
    }
}
