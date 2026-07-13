package io.nova.query.jpql;

import io.nova.metadata.DefaultNamingStrategy;
import io.nova.metadata.EntityMetadataFactory;
import io.nova.query.jpql.ast.JpqlStatement;
import io.nova.sql.BindMarkerStrategy;
import io.nova.sql.Dialect;
import io.nova.sql.SchemaGenerator;
import io.nova.sql.SqlRenderer;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JPQL AST → SQL 문자열 변환기 단위 테스트. double-quote 식별자와 {@code ?} bind marker를 쓰는 테스트
 * dialect로 결정적 SQL을 검증한다. 엔티티/컬럼명은 @Table/@Column/@JoinColumn으로 고정한다.
 */
class JpqlSqlBuilderTest {

    private final Dialect dialect = new TestDialect();
    private final EntityMetadataFactory metadataFactory = new EntityMetadataFactory(new DefaultNamingStrategy());
    private final JpqlEntityResolver resolver =
            new JpqlEntityResolver(metadataFactory, List.of(Employee.class, Department.class));
    private final JpqlSqlBuilder builder = new JpqlSqlBuilder(dialect, resolver);

    private TranslatedSql scalar(String jpql) {
        JpqlStatement.Select select = (JpqlStatement.Select) new JpqlParser(jpql).parse();
        return builder.buildScalarSelect(select);
    }

    @Test
    void rendersScalarProjectionWithWhereAndOrder() {
        TranslatedSql t = scalar("SELECT e.name FROM Employee e WHERE e.salary > :min ORDER BY e.name DESC");
        assertEquals(
                "select e.\"name\" as \"c0\" from \"employee\" e where e.\"salary\" > ? order by e.\"name\" desc",
                t.sql());
        assertEquals(1, t.selectionCount());
        assertEquals(1, t.bindings().size());
        assertEquals(new JpqlBinding.Named("min"), t.bindings().get(0));
    }

    @Test
    void rendersAndPredicateWithParens() {
        TranslatedSql t = scalar("SELECT e.name FROM Employee e WHERE e.age = 30 AND e.name = :n");
        assertEquals(
                "select e.\"name\" as \"c0\" from \"employee\" e where (e.\"age\" = ? and e.\"name\" = ?)",
                t.sql());
        assertEquals(List.of(new JpqlBinding.Literal(30L), new JpqlBinding.Named("n")), t.bindings());
    }

    @Test
    void rendersAggregateGroupByHavingOverManyToOneJoin() {
        TranslatedSql t = scalar("SELECT d.name, COUNT(e) FROM Employee e JOIN e.department d "
                + "GROUP BY d.name HAVING COUNT(e) > 5");
        assertEquals(
                "select d.\"name\" as \"c0\", count(e.\"id\") as \"c1\" "
                        + "from \"employee\" e join \"department\" d on e.\"dept_id\" = d.\"id\" "
                        + "group by d.\"name\" having count(e.\"id\") > ?",
                t.sql());
        assertEquals(2, t.selectionCount());
        assertEquals(List.of(new JpqlBinding.Literal(5L)), t.bindings());
    }

    @Test
    void rendersLeftJoinAndCountStar() {
        TranslatedSql t = scalar("SELECT COUNT(*) FROM Employee e LEFT JOIN e.department d");
        assertEquals(
                "select count(*) as \"c0\" from \"employee\" e left join \"department\" d on e.\"dept_id\" = d.\"id\"",
                t.sql());
    }

    @Test
    void rendersInListAndFunctionsAndArithmetic() {
        TranslatedSql in = scalar("SELECT e.id FROM Employee e WHERE e.id IN (1, 2, 3)");
        assertEquals("select e.\"id\" as \"c0\" from \"employee\" e where e.\"id\" in (?, ?, ?)", in.sql());

        TranslatedSql fn = scalar("SELECT LOWER(e.name) FROM Employee e");
        assertEquals("select lower(e.\"name\") as \"c0\" from \"employee\" e", fn.sql());

        TranslatedSql arith = scalar("SELECT e.salary * 2 FROM Employee e");
        assertEquals("select (e.\"salary\" * ?) as \"c0\" from \"employee\" e", arith.sql());
    }

    @Test
    void rendersCaseExpression() {
        TranslatedSql t = scalar("SELECT CASE WHEN e.salary > 100 THEN 1 ELSE 0 END FROM Employee e");
        assertEquals(
                "select case when e.\"salary\" > ? then ? else ? end as \"c0\" from \"employee\" e",
                t.sql());
        assertEquals(3, t.bindings().size());
    }

    @Test
    void rendersExistsSubqueryWithCorrelation() {
        TranslatedSql t = scalar("SELECT e.name FROM Employee e "
                + "WHERE EXISTS (SELECT 1 FROM Employee m WHERE m.age > e.age)");
        assertEquals(
                "select e.\"name\" as \"c0\" from \"employee\" e where exists ("
                        + "select ? from \"employee\" m where m.\"age\" > e.\"age\")",
                t.sql());
    }

    @Test
    void rendersInSubquery() {
        TranslatedSql t = scalar("SELECT e.name FROM Employee e "
                + "WHERE e.department IN (SELECT d.id FROM Department d WHERE d.name = :n)");
        assertEquals(
                "select e.\"name\" as \"c0\" from \"employee\" e where e.\"dept_id\" in ("
                        + "select d.\"id\" from \"department\" d where d.\"name\" = ?)",
                t.sql());
    }

    @Test
    void rendersBulkUpdateUnqualifiedColumns() {
        JpqlStatement.Update update =
                (JpqlStatement.Update) new JpqlParser("UPDATE Employee e SET e.name = :n, e.age = e.age + 1 WHERE e.id = :id").parse();
        TranslatedSql t = builder.buildUpdate(update);
        assertEquals(
                "update \"employee\" set \"name\" = ?, \"age\" = (\"age\" + ?) where \"id\" = ?",
                t.sql());
        assertEquals(
                List.of(new JpqlBinding.Named("n"), new JpqlBinding.Literal(1L), new JpqlBinding.Named("id")),
                t.bindings());
    }

    @Test
    void rendersBulkDeleteUnqualifiedColumns() {
        JpqlStatement.Delete delete =
                (JpqlStatement.Delete) new JpqlParser("DELETE FROM Employee e WHERE e.age < :max").parse();
        TranslatedSql t = builder.buildDelete(delete);
        assertEquals("delete from \"employee\" where \"age\" < ?", t.sql());
    }

    @Test
    void failsFastOnUnknownEntity() {
        assertThrows(JpqlException.class, () -> scalar("SELECT x.id FROM Unknown x"));
    }

    @Test
    void failsFastOnUnknownField() {
        assertThrows(JpqlException.class, () -> scalar("SELECT e.nope FROM Employee e"));
    }

    @Test
    void failsFastOnUnsupportedFunction() {
        JpqlException ex = assertThrows(JpqlException.class, () -> scalar("SELECT WEIRDFN(e.name) FROM Employee e"));
        assertTrue(ex.getMessage().contains("WEIRDFN"));
    }

    @Test
    void failsFastOnBulkUpdateWithoutAlias() {
        JpqlStatement.Update update =
                (JpqlStatement.Update) new JpqlParser("UPDATE Employee SET name = :n").parse();
        assertThrows(JpqlException.class, () -> builder.buildUpdate(update));
    }

    // ------------------------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------------------------

    @Entity
    @Table(name = "employee")
    public static class Employee {
        @Id
        @Column(name = "id")
        private Long id;
        @Column(name = "name")
        private String name;
        @Column(name = "salary")
        private BigDecimal salary;
        @Column(name = "age")
        private int age;
        @ManyToOne
        @JoinColumn(name = "dept_id")
        private Department department;
    }

    @Entity
    @Table(name = "department")
    public static class Department {
        @Id
        @Column(name = "id")
        private Long id;
        @Column(name = "name")
        private String name;
    }

    private static final class TestDialect implements Dialect {
        private final BindMarkerStrategy bindMarkers = index -> "?";

        @Override
        public String name() {
            return "test";
        }

        @Override
        public String quote(String identifier) {
            return "\"" + identifier + "\"";
        }

        @Override
        public BindMarkerStrategy bindMarkers() {
            return bindMarkers;
        }

        @Override
        public SqlRenderer sqlRenderer() {
            throw new UnsupportedOperationException("not needed for JPQL builder tests");
        }

        @Override
        public SchemaGenerator schemaGenerator() {
            throw new UnsupportedOperationException("not needed for JPQL builder tests");
        }
    }
}
