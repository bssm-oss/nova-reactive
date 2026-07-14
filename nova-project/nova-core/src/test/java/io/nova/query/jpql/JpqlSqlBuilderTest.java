package io.nova.query.jpql;

import io.nova.metadata.DefaultNamingStrategy;
import io.nova.metadata.EntityMetadataFactory;
import io.nova.query.jpql.ast.JpqlStatement;
import io.nova.sql.BindMarkerStrategy;
import io.nova.sql.Dialect;
import io.nova.sql.SchemaGenerator;
import io.nova.sql.SqlRenderer;
import jakarta.persistence.Column;
import jakarta.persistence.DiscriminatorColumn;
import jakarta.persistence.DiscriminatorType;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Inheritance;
import jakarta.persistence.InheritanceType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
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
            new JpqlEntityResolver(metadataFactory, List.of(Employee.class, Department.class, Company.class,
                    Vehicle.class, Car.class, Truck.class));
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
    void rendersImplicitJoinForMultiSegmentPathInWhereAndSelect() {
        TranslatedSql t = scalar(
                "SELECT e.department.name FROM Employee e WHERE e.department.name = :n ORDER BY e.department.name");
        // 같은 (e, department) 경로는 하나의 묵시 조인으로 dedupe되어야 한다.
        assertEquals(
                "select j0.\"name\" as \"c0\" from \"employee\" e "
                        + "join \"department\" j0 on e.\"dept_id\" = j0.\"id\" "
                        + "where j0.\"name\" = ? order by j0.\"name\" asc",
                t.sql());
        assertEquals(List.of(new JpqlBinding.Named("n")), t.bindings());
    }

    @Test
    void rendersChainedImplicitJoinsForDeepPath() {
        TranslatedSql t = scalar("SELECT e.department.company.name FROM Employee e");
        assertEquals(
                "select j1.\"name\" as \"c0\" from \"employee\" e "
                        + "join \"department\" j0 on e.\"dept_id\" = j0.\"id\" "
                        + "join \"company\" j1 on j0.\"company_id\" = j1.\"id\"",
                t.sql());
    }

    @Test
    void rendersCastFunction() {
        TranslatedSql t = scalar("SELECT CAST(e.age AS string) FROM Employee e");
        assertEquals("select cast(e.\"age\" as varchar) as \"c0\" from \"employee\" e", t.sql());
    }

    @Test
    void rendersLocateAndNativeFunction() {
        TranslatedSql locate = scalar("SELECT LOCATE('a', e.name) FROM Employee e");
        assertEquals("select locate(?, e.\"name\") as \"c0\" from \"employee\" e", locate.sql());

        TranslatedSql fn = scalar("SELECT FUNCTION('soundex', e.name) FROM Employee e");
        assertEquals("select soundex(e.\"name\") as \"c0\" from \"employee\" e", fn.sql());
    }

    @Test
    void rendersSizeAsCorrelatedCountSubquery() {
        TranslatedSql t = scalar("SELECT SIZE(d.employees) FROM Department d");
        assertEquals(
                "select (select count(*) from \"employee\" j0 where j0.\"dept_id\" = d.\"id\") as \"c0\" "
                        + "from \"department\" d",
                t.sql());
    }

    @Test
    void rendersConstructorProjectionAsScalarColumns() {
        TranslatedSql t = scalar("SELECT NEW com.x.Dto(e.name, e.age, COUNT(e)) FROM Employee e GROUP BY e.name, e.age");
        assertEquals(
                "select e.\"name\" as \"c0\", e.\"age\" as \"c1\", count(e.\"id\") as \"c2\" "
                        + "from \"employee\" e group by e.\"name\", e.\"age\"",
                t.sql());
        assertEquals(3, t.selectionCount());
    }

    @Test
    void rejectsNativeFunctionNameWithUnsafeCharacters() {
        assertThrows(JpqlException.class,
                () -> scalar("SELECT FUNCTION('drop table x;--', e.name) FROM Employee e"));
    }

    @Test
    void rejectsUnknownCastType() {
        assertThrows(JpqlException.class, () -> scalar("SELECT CAST(e.name AS geometry) FROM Employee e"));
    }

    @Test
    void rejectsMultiSegmentPathInBulkUpdate() {
        JpqlStatement.Update update = (JpqlStatement.Update) new JpqlParser(
                "UPDATE Employee e SET e.name = :n WHERE e.department.name = :d").parse();
        assertThrows(JpqlException.class, () -> builder.buildUpdate(update));
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
    void failsFastOnJoinFetchInScalarProjection() {
        // JOIN FETCH 는 엔티티 반환 SELECT에서만 유효하다. 스칼라 투영 경로로 새면 fail-fast.
        JpqlException ex = assertThrows(JpqlException.class,
                () -> scalar("SELECT e.name FROM Employee e JOIN FETCH e.department d"));
        assertTrue(ex.getMessage().contains("JOIN FETCH"));
    }

    @Test
    void failsFastOnBulkUpdateWithoutAlias() {
        JpqlStatement.Update update =
                (JpqlStatement.Update) new JpqlParser("UPDATE Employee SET name = :n").parse();
        assertThrows(JpqlException.class, () -> builder.buildUpdate(update));
    }

    // ------------------------------------------------------------------------------------
    // 복합키 타겟 to-one: join ON 과 terminal 비교/IS NULL 을 모든 FK 컴포넌트로 전개한다(다중컬럼 FK).
    // SELECT 투영처럼 단일 컬럼으로 축약 불가한 자리만 fail-fast로 남는다.
    // ------------------------------------------------------------------------------------

    private JpqlSqlBuilder compositeBuilder() {
        JpqlEntityResolver compositeResolver = new JpqlEntityResolver(metadataFactory,
                List.of(io.nova.support.fixtures.FixtureEntities.CompositeJoinChild.class,
                        io.nova.support.fixtures.FixtureEntities.CompositeJoinParent.class));
        return new JpqlSqlBuilder(dialect, compositeResolver);
    }

    private TranslatedSql compositeScalar(String jpql) {
        JpqlStatement.Select select = (JpqlStatement.Select) new JpqlParser(jpql).parse();
        return compositeBuilder().buildScalarSelect(select);
    }

    @Test
    void explicitJoinOverCompositeKeyToOneRendersAllOnComponents() {
        TranslatedSql t = compositeScalar("SELECT c.id FROM CompositeJoinChild c JOIN c.parent p");
        assertEquals(
                "select c.\"id\" as \"c0\" from \"gc_composite_child\" c "
                        + "join \"gc_composite_parent\" p on c.\"p_k1\" = p.\"k1\" and c.\"p_k2\" = p.\"k2\"",
                t.sql());
    }

    @Test
    void implicitJoinOverCompositeKeyToOneRendersAllOnComponents() {
        // 중간 세그먼트(parent)가 복합키 to-one이면 각 레벨에서 다중컬럼 ON을 만든 뒤 마지막 스칼라 컬럼을 해석한다.
        TranslatedSql t = compositeScalar("SELECT c.id FROM CompositeJoinChild c WHERE c.parent.label = 'x'");
        assertEquals(
                "select c.\"id\" as \"c0\" from \"gc_composite_child\" c "
                        + "join \"gc_composite_parent\" j0 on c.\"p_k1\" = j0.\"k1\" and c.\"p_k2\" = j0.\"k2\" "
                        + "where j0.\"label\" = ?",
                t.sql());
    }

    @Test
    void terminalReferenceToCompositeKeyToOneExpandsToComponentEquality() {
        TranslatedSql t = compositeScalar("SELECT c.id FROM CompositeJoinChild c WHERE c.parent = :p");
        assertEquals(
                "select c.\"id\" as \"c0\" from \"gc_composite_child\" c where (c.\"p_k1\" = ? and c.\"p_k2\" = ?)",
                t.sql());
        assertEquals(2, t.bindings().size());
        JpqlBinding.Component first = assertComponent(t.bindings().get(0));
        JpqlBinding.Component second = assertComponent(t.bindings().get(1));
        assertEquals(new JpqlBinding.Named("p"), first.source());
        assertEquals(new JpqlBinding.Named("p"), second.source());
        assertEquals("p_k1", first.column().columnName());
        assertEquals("p_k2", second.column().columnName());
    }

    @Test
    void terminalNotEqualToCompositeKeyToOneExpandsToComponentInequalityWithOr() {
        // {@code <>}는 튜플 부등이므로 각 FK 컴포넌트 neq의 OR로 전개된다({@code =}의 AND와 대비).
        TranslatedSql t = compositeScalar("SELECT c.id FROM CompositeJoinChild c WHERE c.parent <> :p");
        assertEquals(
                "select c.\"id\" as \"c0\" from \"gc_composite_child\" c where (c.\"p_k1\" <> ? or c.\"p_k2\" <> ?)",
                t.sql());
        assertEquals(2, t.bindings().size());
        JpqlBinding.Component first = assertComponent(t.bindings().get(0));
        JpqlBinding.Component second = assertComponent(t.bindings().get(1));
        assertEquals(new JpqlBinding.Named("p"), first.source());
        assertEquals(new JpqlBinding.Named("p"), second.source());
        assertEquals("p_k1", first.column().columnName());
        assertEquals("p_k2", second.column().columnName());
    }

    @Test
    void terminalIsNullOverCompositeKeyToOneExpandsToAllForeignKeyColumns() {
        TranslatedSql t = compositeScalar("SELECT c.id FROM CompositeJoinChild c WHERE c.parent IS NULL");
        assertEquals(
                "select c.\"id\" as \"c0\" from \"gc_composite_child\" c "
                        + "where (c.\"p_k1\" is null and c.\"p_k2\" is null)",
                t.sql());
        assertTrue(t.bindings().isEmpty());
    }

    private static JpqlBinding.Component assertComponent(JpqlBinding binding) {
        assertTrue(binding instanceof JpqlBinding.Component, "expected component binding, got " + binding);
        return (JpqlBinding.Component) binding;
    }

    @Test
    void failsFastOnTerminalSelectionOfCompositeKeyToOne() {
        JpqlSqlBuilder b = compositeBuilder();
        JpqlStatement.Select select = (JpqlStatement.Select) new JpqlParser(
                "SELECT c.parent FROM CompositeJoinChild c").parse();
        JpqlException ex = assertThrows(JpqlException.class, () -> b.buildScalarSelect(select));
        assertTrue(ex.getMessage().contains("composite-key"));
    }

    // ------------------------------------------------------------------------------------
    // TYPE() / TREAT() polymorphism
    // ------------------------------------------------------------------------------------

    @Test
    void rendersTypeEqualityAsDiscriminatorPredicate() {
        TranslatedSql t = scalar("SELECT e.name FROM Vehicle e WHERE TYPE(e) = Car");
        assertEquals(
                "select e.\"name\" as \"c0\" from \"vehicle\" e where e.\"kind\" = ?",
                t.sql());
        assertEquals(List.of(new JpqlBinding.Literal("CAR")), t.bindings());
    }

    @Test
    void rendersTypeInAsDiscriminatorInList() {
        TranslatedSql t = scalar("SELECT COUNT(e) FROM Vehicle e WHERE TYPE(e) IN (Car, Truck)");
        assertEquals(
                "select count(e.\"id\") as \"c0\" from \"vehicle\" e where e.\"kind\" in (?, ?)",
                t.sql());
        assertEquals(List.of(new JpqlBinding.Literal("CAR"), new JpqlBinding.Literal("TRUCK")), t.bindings());
    }

    @Test
    void rendersTreatProjectionWithDiscriminatorFilter() {
        TranslatedSql t = scalar("SELECT TREAT(e AS Car).doors FROM Vehicle e");
        assertEquals(
                "select e.\"doors\" as \"c0\" from \"vehicle\" e where e.\"kind\" = ?",
                t.sql());
        assertEquals(List.of(new JpqlBinding.Literal("CAR")), t.bindings());
    }

    @Test
    void rendersConcreteSubtypeRootWithAutomaticDiscriminator() {
        TranslatedSql t = scalar("SELECT c.doors FROM Car c");
        assertEquals(
                "select c.\"doors\" as \"c0\" from \"vehicle\" c where c.\"kind\" = ?",
                t.sql());
        assertEquals(List.of(new JpqlBinding.Literal("CAR")), t.bindings());
    }

    @Test
    void failsFastOnTypeForNonInheritanceEntity() {
        JpqlException ex = assertThrows(JpqlException.class, () -> scalar("SELECT TYPE(e) FROM Employee e"));
        assertTrue(ex.getMessage().contains("Inheritance"));
    }

    @Test
    void failsFastOnTreatToUnrelatedType() {
        assertThrows(JpqlException.class, () -> scalar("SELECT TREAT(e AS Truck).payload FROM Company e"));
    }

    @Test
    void failsFastOnConcreteSubtypeAsSubqueryRoot() {
        // 서브쿼리 안에서는 discriminator 제한이 주입되지 않으므로, 구체 서브타입 root는 공유 테이블을
        // 필터 없이 풀어 Truck까지 매칭한다 — 조용한 오답 대신 fail-fast여야 한다.
        JpqlException ex = assertThrows(JpqlException.class, () ->
                scalar("SELECT v.name FROM Vehicle v WHERE EXISTS (SELECT 1 FROM Car c WHERE c.doors > 0)"));
        assertTrue(ex.getMessage().contains("subquery"));
    }

    @Test
    void failsFastOnTreatInsideSubquery() {
        JpqlException ex = assertThrows(JpqlException.class, () ->
                scalar("SELECT v.name FROM Vehicle v WHERE v.id IN (SELECT TREAT(w AS Car).doors FROM Vehicle w)"));
        assertTrue(ex.getMessage().contains("subquery"));
    }

    @Test
    void failsFastOnTypeInsideSubqueryPredicate() {
        assertThrows(JpqlException.class, () ->
                scalar("SELECT v.name FROM Vehicle v WHERE v.id IN "
                        + "(SELECT w.id FROM Vehicle w WHERE TYPE(w) = Car)"));
    }

    // ------------------------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------------------------

    @Entity
    @Table(name = "vehicle")
    @Inheritance(strategy = InheritanceType.SINGLE_TABLE)
    @DiscriminatorColumn(name = "kind", discriminatorType = DiscriminatorType.STRING)
    public abstract static class Vehicle {
        @Id
        @Column(name = "id")
        private Long id;
        @Column(name = "name")
        private String name;
    }

    @Entity
    @DiscriminatorValue("CAR")
    public static class Car extends Vehicle {
        @Column(name = "doors")
        private int doors;
    }

    @Entity
    @DiscriminatorValue("TRUCK")
    public static class Truck extends Vehicle {
        @Column(name = "payload")
        private double payload;
    }

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
        @OneToMany(mappedBy = "department")
        private java.util.List<Employee> employees;
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
