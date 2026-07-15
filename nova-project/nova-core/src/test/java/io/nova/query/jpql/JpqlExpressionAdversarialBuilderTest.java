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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 독립 adversarial SQL-builder 테스트. double-quote 식별자 + {@code ?} bind marker의 결정적 test dialect로
 * Track B가 생성하는 SQL 문자열과 bind 순서를 저자와 무관하게 재검증한다.
 */
class JpqlExpressionAdversarialBuilderTest {

    private final Dialect dialect = new TestDialect();
    private final EntityMetadataFactory metadataFactory = new EntityMetadataFactory(new DefaultNamingStrategy());
    private final JpqlEntityResolver resolver = new JpqlEntityResolver(metadataFactory,
            List.of(Employee.class, Department.class, Company.class));
    private final JpqlSqlBuilder builder = new JpqlSqlBuilder(dialect, resolver);

    private TranslatedSql scalar(String jpql) {
        JpqlStatement.Select select = (JpqlStatement.Select) new JpqlParser(jpql).parse();
        return builder.buildScalarSelect(select);
    }

    // ---- Edge 1: 함수형 LEFT/RIGHT + 조인형 LEFT JOIN 이 한 쿼리에서 안 깨진다 ----------------

    @Test
    void leftFunctionAndLeftJoinRenderIndependently() {
        TranslatedSql t = scalar(
                "SELECT LEFT(e.name, 2) FROM Employee e LEFT JOIN e.department d WHERE d.name = 'Eng'");
        assertEquals(
                "select left(e.\"name\", ?) as \"c0\" from \"employee\" e "
                        + "left join \"department\" d on e.\"dept_id\" = d.\"id\" where d.\"name\" = ?",
                t.sql());
        // bind 순서: SELECT의 리터럴 2가 먼저, 그 다음 WHERE의 'Eng'.
        assertEquals(List.of(new JpqlBinding.Literal(2L), new JpqlBinding.Literal("Eng")), t.bindings());
    }

    @Test
    void rightFunctionAndChainedJoinsRenderIndependently() {
        TranslatedSql t = scalar(
                "SELECT RIGHT(e.name, 3) FROM Employee e LEFT JOIN e.department d INNER JOIN d.company c "
                        + "WHERE c.name = :co");
        assertEquals(
                "select right(e.\"name\", ?) as \"c0\" from \"employee\" e "
                        + "left join \"department\" d on e.\"dept_id\" = d.\"id\" "
                        + "join \"company\" c on d.\"company_id\" = c.\"id\" where c.\"name\" = ?",
                t.sql());
        assertEquals(List.of(new JpqlBinding.Literal(3L), new JpqlBinding.Named("co")), t.bindings());
    }

    @Test
    void nestedLeftOfRightRenders() {
        TranslatedSql t = scalar("SELECT LEFT(RIGHT(e.name, 3), 2) FROM Employee e");
        assertEquals("select left(right(e.\"name\", ?), ?) as \"c0\" from \"employee\" e", t.sql());
        assertEquals(List.of(new JpqlBinding.Literal(3L), new JpqlBinding.Literal(2L)), t.bindings());
    }

    @Test
    void bindOrderIsSelectThenWhereForParameterizedLeftFunction() {
        TranslatedSql t = scalar("SELECT LEFT(e.name, :n) FROM Employee e WHERE e.age > :a");
        assertEquals(
                "select left(e.\"name\", ?) as \"c0\" from \"employee\" e where e.\"age\" > ?",
                t.sql());
        assertEquals(List.of(new JpqlBinding.Named("n"), new JpqlBinding.Named("a")), t.bindings());
    }

    // ---- Edge 2: <> ALL 렌더 ----------------------------------------------------------------

    @Test
    void notEqualAllRendersNativeAllClause() {
        TranslatedSql t = scalar(
                "SELECT e.id FROM Employee e WHERE e.salary <> ALL (SELECT m.salary FROM Employee m)");
        assertEquals(
                "select e.\"id\" as \"c0\" from \"employee\" e where e.\"salary\" <> all ("
                        + "select m.\"salary\" from \"employee\" m)",
                t.sql());
    }

    // ---- Edge 7: 함수 인자/리터럴이 bind marker 경유(인젝션 없음) -----------------------------

    @Test
    void replaceWithNamedParametersBindsThroughMarkers() {
        TranslatedSql t = scalar("SELECT REPLACE(e.name, :a, :b) FROM Employee e");
        assertEquals("select replace(e.\"name\", ?, ?) as \"c0\" from \"employee\" e", t.sql());
        assertEquals(List.of(new JpqlBinding.Named("a"), new JpqlBinding.Named("b")), t.bindings());
    }

    @Test
    void replaceWithHostileStringLiteralIsParameterizedNotInlined() {
        TranslatedSql t = scalar("SELECT REPLACE(e.name, ';DROP TABLE x;--', 'y') FROM Employee e");
        assertEquals("select replace(e.\"name\", ?, ?) as \"c0\" from \"employee\" e", t.sql());
        assertFalse(t.sql().toUpperCase(java.util.Locale.ROOT).contains("DROP"),
                "hostile literal must not be inlined into SQL: " + t.sql());
        assertEquals(new JpqlBinding.Literal(";DROP TABLE x;--"), t.bindings().get(0));
        assertEquals(new JpqlBinding.Literal("y"), t.bindings().get(1));
    }

    // ---- Edge 3: EXTRACT 화이트리스트 밖 필드 거부 / 허용 필드 렌더 -----------------------------

    @Test
    void extractRejectsNonPortableFields() {
        for (String field : new String[] {"DOY", "TIMEZONE_HOUR", "EPOCH", "MICROSECOND", "CENTURY"}) {
            assertThrows(JpqlException.class,
                    () -> scalar("SELECT EXTRACT(" + field + " FROM e.age) FROM Employee e"),
                    "EXTRACT field '" + field + "' must be rejected");
        }
    }

    @Test
    void extractAllowedFieldsRenderLowercased() {
        assertEquals("select extract(hour from e.\"age\") as \"c0\" from \"employee\" e",
                scalar("SELECT EXTRACT(HOUR FROM e.age) FROM Employee e").sql());
        assertEquals("select extract(week from e.\"age\") as \"c0\" from \"employee\" e",
                scalar("SELECT EXTRACT(WEEK FROM e.age) FROM Employee e").sql());
        assertEquals("select extract(quarter from e.\"age\") as \"c0\" from \"employee\" e",
                scalar("SELECT EXTRACT(QUARTER FROM e.age) FROM Employee e").sql());
        assertEquals("select extract(second from e.\"age\") as \"c0\" from \"employee\" e",
                scalar("SELECT EXTRACT(SECOND FROM e.age) FROM Employee e").sql());
    }

    // ---- Edge 4: TRIM 4형태의 정확한 SQL ------------------------------------------------------

    @Test
    void trimBothFromWithoutCharRenders() {
        assertEquals("select trim(both from e.\"name\") as \"c0\" from \"employee\" e",
                scalar("SELECT TRIM(BOTH FROM e.name) FROM Employee e").sql());
    }

    @Test
    void trimBothCharFromRendersWithBoundChar() {
        TranslatedSql t = scalar("SELECT TRIM(BOTH ' ' FROM e.name) FROM Employee e");
        assertEquals("select trim(both ? from e.\"name\") as \"c0\" from \"employee\" e", t.sql());
        assertEquals(List.of(new JpqlBinding.Literal(" ")), t.bindings());
    }

    @Test
    void plainTrimHasNoRegression() {
        assertEquals("select trim(e.\"name\") as \"c0\" from \"employee\" e",
                scalar("SELECT TRIM(e.name) FROM Employee e").sql());
    }

    // ---- Edge 5: LOCAL DATE/TIME/DATETIME 매핑 ------------------------------------------------

    @Test
    void localTemporalFunctionsMapToPortableCurrentKeywords() {
        // LOCAL DATE는 tz 개념이 없어 current_date로 정확히 매핑된다. LOCAL TIME/DATETIME은 tz-less
        // 시맨틱인데 current_time/current_timestamp가 PostgreSQL에서 tz-aware이므로, 표준 SQL의 tz-less
        // 키워드 localtime/localtimestamp로 매핑한다(H2/PG/MySQL 공통 수용).
        assertEquals("select current_date as \"c0\" from \"employee\" e",
                scalar("SELECT LOCAL DATE FROM Employee e").sql());
        assertEquals("select localtime as \"c0\" from \"employee\" e",
                scalar("SELECT LOCAL TIME FROM Employee e").sql());
        assertEquals("select localtimestamp as \"c0\" from \"employee\" e",
                scalar("SELECT LOCAL DATETIME FROM Employee e").sql());
    }

    // ---- Edge 6: KEY/VALUE/ENTRY/INDEX 는 빌더 이전 파서에서 거부(빌더까지 새지 않음) -----------

    @Test
    void collectionQualifierFunctionsNeverReachBuilder() {
        assertThrows(JpqlSyntaxException.class,
                () -> scalar("SELECT KEY(m) FROM Employee e"));
        assertThrows(JpqlSyntaxException.class,
                () -> scalar("SELECT VALUE(m) FROM Employee e"));
    }

    // ------------------------------------------------------------------------------------
    // Fixtures + deterministic test dialect
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
        @ManyToOne
        @JoinColumn(name = "company_id")
        private Company company;
    }

    @Entity
    @Table(name = "company")
    public static class Company {
        @Id
        @Column(name = "id")
        private Long id;
        @Column(name = "name")
        private String name;
    }

    private static final class TestDialect implements Dialect {
        private final BindMarkerStrategy bindMarkers = index -> "?";
        private final SqlRenderer renderer = new io.nova.sql.AbstractSqlRenderer(this) {
        };

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
            return renderer;
        }

        @Override
        public SchemaGenerator schemaGenerator() {
            throw new UnsupportedOperationException("not needed for JPQL builder tests");
        }
    }
}
