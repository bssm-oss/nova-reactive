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

    // ------------------------------------------------------------------------------------
    // ANY / ALL quantified subquery comparison
    // ------------------------------------------------------------------------------------

    @Test
    void rendersEqualAnyQuantifiedSubquery() {
        TranslatedSql t = scalar(
                "SELECT e.id FROM Employee e WHERE e.salary = ANY (SELECT m.salary FROM Employee m)");
        assertEquals(
                "select e.\"id\" as \"c0\" from \"employee\" e where e.\"salary\" = any ("
                        + "select m.\"salary\" from \"employee\" m)",
                t.sql());
    }

    @Test
    void rendersGreaterAllQuantifiedSubquery() {
        TranslatedSql t = scalar(
                "SELECT e.id FROM Employee e WHERE e.salary > ALL (SELECT m.salary FROM Employee m WHERE m.age < :a)");
        assertEquals(
                "select e.\"id\" as \"c0\" from \"employee\" e where e.\"salary\" > all ("
                        + "select m.\"salary\" from \"employee\" m where m.\"age\" < ?)",
                t.sql());
        assertEquals(List.of(new JpqlBinding.Named("a")), t.bindings());
    }

    @Test
    void normalizesSomeToAnyInRenderedSql() {
        TranslatedSql t = scalar(
                "SELECT e.id FROM Employee e WHERE e.salary <= SOME (SELECT m.salary FROM Employee m)");
        assertTrue(t.sql().contains("<= any ("), t.sql());
    }

    // ------------------------------------------------------------------------------------
    // JPA 3.1 scalar functions: extended set, EXTRACT, TRIM modifiers, LOCAL temporal
    // ------------------------------------------------------------------------------------

    @Test
    void rendersExtendedScalarFunctions() {
        assertEquals("select ceiling(e.\"salary\") as \"c0\" from \"employee\" e",
                scalar("SELECT CEILING(e.salary) FROM Employee e").sql());
        assertEquals("select floor(e.\"salary\") as \"c0\" from \"employee\" e",
                scalar("SELECT FLOOR(e.salary) FROM Employee e").sql());
        assertEquals("select sign(e.\"salary\") as \"c0\" from \"employee\" e",
                scalar("SELECT SIGN(e.salary) FROM Employee e").sql());
        assertEquals("select exp(e.\"salary\") as \"c0\" from \"employee\" e",
                scalar("SELECT EXP(e.salary) FROM Employee e").sql());
        assertEquals("select ln(e.\"salary\") as \"c0\" from \"employee\" e",
                scalar("SELECT LN(e.salary) FROM Employee e").sql());
        assertEquals("select power(e.\"salary\", ?) as \"c0\" from \"employee\" e",
                scalar("SELECT POWER(e.salary, 2) FROM Employee e").sql());
        assertEquals("select round(e.\"salary\", ?) as \"c0\" from \"employee\" e",
                scalar("SELECT ROUND(e.salary, 0) FROM Employee e").sql());
        assertEquals("select left(e.\"name\", ?) as \"c0\" from \"employee\" e",
                scalar("SELECT LEFT(e.name, 3) FROM Employee e").sql());
        assertEquals("select right(e.\"name\", ?) as \"c0\" from \"employee\" e",
                scalar("SELECT RIGHT(e.name, 2) FROM Employee e").sql());
        assertEquals("select replace(e.\"name\", ?, ?) as \"c0\" from \"employee\" e",
                scalar("SELECT REPLACE(e.name, 'a', 'A') FROM Employee e").sql());
    }

    @Test
    void rendersExtractField() {
        // 필드는 화이트리스트로 검증돼 그대로 방출된다(SQL-shape 검증 — 컬럼 타입은 무관).
        assertEquals("select extract(year from e.\"age\") as \"c0\" from \"employee\" e",
                scalar("SELECT EXTRACT(YEAR FROM e.age) FROM Employee e").sql());
        assertEquals("select extract(month from e.\"age\") as \"c0\" from \"employee\" e",
                scalar("SELECT EXTRACT(MONTH FROM e.age) FROM Employee e").sql());
    }

    @Test
    void rejectsUnknownExtractField() {
        assertThrows(JpqlException.class, () -> scalar("SELECT EXTRACT(CENTURY FROM e.age) FROM Employee e"));
    }

    @Test
    void rendersTrimModifiers() {
        assertEquals("select trim(leading from e.\"name\") as \"c0\" from \"employee\" e",
                scalar("SELECT TRIM(LEADING FROM e.name) FROM Employee e").sql());
        assertEquals("select trim(trailing ? from e.\"name\") as \"c0\" from \"employee\" e",
                scalar("SELECT TRIM(TRAILING 'x' FROM e.name) FROM Employee e").sql());
        assertEquals("select trim(both ? from e.\"name\") as \"c0\" from \"employee\" e",
                scalar("SELECT TRIM(BOTH 'x' FROM e.name) FROM Employee e").sql());
        assertEquals("select trim(? from e.\"name\") as \"c0\" from \"employee\" e",
                scalar("SELECT TRIM('x' FROM e.name) FROM Employee e").sql());
        // 회귀: 평범한 TRIM(x)는 기존과 동일하게 렌더된다.
        assertEquals("select trim(e.\"name\") as \"c0\" from \"employee\" e",
                scalar("SELECT TRIM(e.name) FROM Employee e").sql());
    }

    @Test
    void rendersLocalTemporalFunctions() {
        assertEquals("select current_date as \"c0\" from \"employee\" e",
                scalar("SELECT LOCAL DATE FROM Employee e").sql());
        assertEquals("select current_time as \"c0\" from \"employee\" e",
                scalar("SELECT LOCAL TIME FROM Employee e").sql());
        assertEquals("select current_timestamp as \"c0\" from \"employee\" e",
                scalar("SELECT LOCAL DATETIME FROM Employee e").sql());
    }

    // ------------------------------------------------------------------------------------
    // COALESCE / NULLIF (already first-class; coverage lock)
    // ------------------------------------------------------------------------------------

    @Test
    void rendersCoalesceAndNullif() {
        assertEquals("select coalesce(e.\"name\", ?) as \"c0\" from \"employee\" e",
                scalar("SELECT COALESCE(e.name, :d) FROM Employee e").sql());
        assertEquals("select nullif(e.\"age\", ?) as \"c0\" from \"employee\" e",
                scalar("SELECT NULLIF(e.age, 0) FROM Employee e").sql());
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
    void terminalProjectionOfCompositeKeyToOneRendersAllForeignKeyColumnsAsOneLogicalSlot() {
        // SELECT c.parent(복합 to-one)은 대표 컬럼 1개로 축약할 수 없으므로 FK 컴포넌트 전부를 canonical
        // ToOneForeignKey 순서(p_k1, p_k2)대로 렌더하고, 논리 슬롯은 1개(물리 컬럼 2개)로 묶는다.
        TranslatedSql t = compositeScalar("SELECT c.parent FROM CompositeJoinChild c");
        assertEquals(
                "select c.\"p_k1\" as \"c0\", c.\"p_k2\" as \"c1\" from \"gc_composite_child\" c",
                t.sql());
        assertEquals(2, t.selectionCount());
        assertTrue(t.bindings().isEmpty());
        assertEquals(1, t.slots().size());
        TranslatedSql.ResultSlot slot = t.slots().get(0);
        assertEquals(0, slot.firstColumn());
        assertEquals(2, slot.columnCount());
        assertEquals(List.of("p_k1", "p_k2"), slot.compositeFk().columns().stream()
                .map(io.nova.metadata.ToOneForeignKeyColumn::columnName).toList());
    }

    @Test
    void mixedScalarAndCompositeToOneProjectionProducesTwoSlots() {
        // SELECT c.id, c.parent 는 scalar 슬롯 1개(물리 컬럼 0) + composite 슬롯 1개(물리 컬럼 1..2)가 된다.
        TranslatedSql t = compositeScalar("SELECT c.id, c.parent FROM CompositeJoinChild c");
        assertEquals(
                "select c.\"id\" as \"c0\", c.\"p_k1\" as \"c1\", c.\"p_k2\" as \"c2\" "
                        + "from \"gc_composite_child\" c",
                t.sql());
        assertEquals(3, t.selectionCount());
        assertEquals(2, t.slots().size());
        TranslatedSql.ResultSlot scalarSlot = t.slots().get(0);
        assertEquals(0, scalarSlot.firstColumn());
        assertEquals(1, scalarSlot.columnCount());
        assertEquals(null, scalarSlot.compositeFk());
        TranslatedSql.ResultSlot compositeSlot = t.slots().get(1);
        assertEquals(1, compositeSlot.firstColumn());
        assertEquals(2, compositeSlot.columnCount());
        assertTrue(compositeSlot.compositeFk() != null);
    }

    @Test
    void selectNewConstructorArgumentRejectsCompositeKeyToOne() {
        JpqlSqlBuilder b = compositeBuilder();
        JpqlStatement.Select select = (JpqlStatement.Select) new JpqlParser(
                "SELECT NEW com.x.Dto(c.id, c.parent) FROM CompositeJoinChild c").parse();
        JpqlException ex = assertThrows(JpqlException.class, () -> b.buildScalarSelect(select));
        assertTrue(ex.getMessage().contains("SELECT NEW"));
    }

    @Test
    void terminalLessThanCompositeKeyToOneExpandsLexicographically() {
        // (p_k1, p_k2) < (ref) → (p_k1 < ?) or (p_k1 = ? and p_k2 < ?). 컴포넌트 순서는 canonical FK 순서.
        TranslatedSql t = compositeScalar("SELECT c.id FROM CompositeJoinChild c WHERE c.parent < :p");
        assertEquals(
                "select c.\"id\" as \"c0\" from \"gc_composite_child\" c where "
                        + "((c.\"p_k1\" < ?) or (c.\"p_k1\" = ? and c.\"p_k2\" < ?))",
                t.sql());
        assertEquals(3, t.bindings().size());
        assertEquals("p_k1", assertComponent(t.bindings().get(0)).column().columnName());
        assertEquals("p_k1", assertComponent(t.bindings().get(1)).column().columnName());
        assertEquals("p_k2", assertComponent(t.bindings().get(2)).column().columnName());
    }

    @Test
    void terminalLessOrEqualCompositeKeyToOneMakesLastComponentNonStrict() {
        // <= 는 마지막 컴포넌트만 non-strict(<=)로 두어 튜플 동등 케이스를 포함한다.
        TranslatedSql t = compositeScalar("SELECT c.id FROM CompositeJoinChild c WHERE c.parent <= :p");
        assertEquals(
                "select c.\"id\" as \"c0\" from \"gc_composite_child\" c where "
                        + "((c.\"p_k1\" < ?) or (c.\"p_k1\" = ? and c.\"p_k2\" <= ?))",
                t.sql());
        assertEquals(3, t.bindings().size());
    }

    @Test
    void terminalGreaterThanCompositeKeyToOneExpandsLexicographically() {
        TranslatedSql t = compositeScalar("SELECT c.id FROM CompositeJoinChild c WHERE c.parent > :p");
        assertEquals(
                "select c.\"id\" as \"c0\" from \"gc_composite_child\" c where "
                        + "((c.\"p_k1\" > ?) or (c.\"p_k1\" = ? and c.\"p_k2\" > ?))",
                t.sql());
    }

    @Test
    void reversedOperandOrderFlipsCompositeOrdering() {
        // :p > c.parent 는 c.parent < :p 와 동치이므로 strict-less 전개로 렌더돼야 한다.
        TranslatedSql t = compositeScalar("SELECT c.id FROM CompositeJoinChild c WHERE :p > c.parent");
        assertEquals(
                "select c.\"id\" as \"c0\" from \"gc_composite_child\" c where "
                        + "((c.\"p_k1\" < ?) or (c.\"p_k1\" = ? and c.\"p_k2\" < ?))",
                t.sql());
    }

    @Test
    void terminalBetweenCompositeKeyToOneExpandsToGreaterEqualAndLessEqual() {
        TranslatedSql t = compositeScalar(
                "SELECT c.id FROM CompositeJoinChild c WHERE c.parent BETWEEN :lo AND :hi");
        assertEquals(
                "select c.\"id\" as \"c0\" from \"gc_composite_child\" c where "
                        + "(((c.\"p_k1\" > ?) or (c.\"p_k1\" = ? and c.\"p_k2\" >= ?)) and "
                        + "((c.\"p_k1\" < ?) or (c.\"p_k1\" = ? and c.\"p_k2\" <= ?)))",
                t.sql());
        assertEquals(6, t.bindings().size());
        assertEquals(new JpqlBinding.Named("lo"), assertComponent(t.bindings().get(0)).source());
        assertEquals(new JpqlBinding.Named("hi"), assertComponent(t.bindings().get(3)).source());
    }

    @Test
    void terminalInListCompositeKeyToOneExpandsToOrOfAnds() {
        TranslatedSql t = compositeScalar(
                "SELECT c.id FROM CompositeJoinChild c WHERE c.parent IN (:a, :b)");
        assertEquals(
                "select c.\"id\" as \"c0\" from \"gc_composite_child\" c where "
                        + "((c.\"p_k1\" = ? and c.\"p_k2\" = ?) or (c.\"p_k1\" = ? and c.\"p_k2\" = ?))",
                t.sql());
        assertEquals(4, t.bindings().size());
        assertEquals(new JpqlBinding.Named("a"), assertComponent(t.bindings().get(0)).source());
        assertEquals(new JpqlBinding.Named("b"), assertComponent(t.bindings().get(2)).source());
    }

    @Test
    void terminalNotInCompositeKeyToOneNegatesOrOfAnds() {
        TranslatedSql t = compositeScalar(
                "SELECT c.id FROM CompositeJoinChild c WHERE c.parent NOT IN (:a)");
        assertEquals(
                "select c.\"id\" as \"c0\" from \"gc_composite_child\" c where "
                        + "not ((c.\"p_k1\" = ? and c.\"p_k2\" = ?))",
                t.sql());
    }

    @Test
    void orderByAscOverCompositeKeyToOneExpandsAllForeignKeyColumnsAscending() {
        TranslatedSql t = compositeScalar("SELECT c.id FROM CompositeJoinChild c ORDER BY c.parent ASC");
        assertEquals(
                "select c.\"id\" as \"c0\" from \"gc_composite_child\" c "
                        + "order by c.\"p_k1\" asc, c.\"p_k2\" asc",
                t.sql());
    }

    @Test
    void orderByDescOverCompositeKeyToOneAppliesSameDirectionToAllComponents() {
        TranslatedSql t = compositeScalar("SELECT c.id FROM CompositeJoinChild c ORDER BY c.parent DESC");
        assertEquals(
                "select c.\"id\" as \"c0\" from \"gc_composite_child\" c "
                        + "order by c.\"p_k1\" desc, c.\"p_k2\" desc",
                t.sql());
    }

    @Test
    void orderByInterleavesCompositeKeyToOneExpansionWithScalarItemsAndCommaBookkeepingStaysCorrect() {
        TranslatedSql t = compositeScalar(
                "SELECT c.id FROM CompositeJoinChild c ORDER BY c.parent ASC, c.label DESC");
        assertEquals(
                "select c.\"id\" as \"c0\" from \"gc_composite_child\" c "
                        + "order by c.\"p_k1\" asc, c.\"p_k2\" asc, c.\"label\" desc",
                t.sql());
    }

    @Test
    void groupByOverCompositeKeyToOneExpandsToAllForeignKeyColumns() {
        TranslatedSql t = compositeScalar(
                "SELECT COUNT(c.id) FROM CompositeJoinChild c GROUP BY c.parent");
        assertEquals(
                "select count(c.\"id\") as \"c0\" from \"gc_composite_child\" c "
                        + "group by c.\"p_k1\", c.\"p_k2\"",
                t.sql());
    }

    @Test
    void groupByOverCompositeKeyToOneWithHavingAggregateRendersExpandedGroupAndHaving() {
        TranslatedSql t = compositeScalar(
                "SELECT COUNT(c.id) FROM CompositeJoinChild c GROUP BY c.parent HAVING COUNT(c.id) > 1");
        assertEquals(
                "select count(c.\"id\") as \"c0\" from \"gc_composite_child\" c "
                        + "group by c.\"p_k1\", c.\"p_k2\" having count(c.\"id\") > ?",
                t.sql());
        assertEquals(List.of(new JpqlBinding.Literal(1L)), t.bindings());
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
    // TYPE() / TREAT() polymorphism over JOINED / TABLE_PER_CLASS inheritance
    // ------------------------------------------------------------------------------------

    private JpqlSqlBuilder joinedBuilder() {
        JpqlEntityResolver r = new JpqlEntityResolver(metadataFactory, List.of(JVehicle.class, JCar.class, JTruck.class));
        return new JpqlSqlBuilder(dialect, r);
    }

    private TranslatedSql joinedScalar(String jpql) {
        JpqlStatement.Select select = (JpqlStatement.Select) new JpqlParser(jpql).parse();
        return joinedBuilder().buildScalarSelect(select);
    }

    private JpqlSqlBuilder tpcBuilder() {
        JpqlEntityResolver r = new JpqlEntityResolver(metadataFactory, List.of(TVehicle.class, TCar.class, TTruck.class));
        return new JpqlSqlBuilder(dialect, r);
    }

    private TranslatedSql tpcScalar(String jpql) {
        JpqlStatement.Select select = (JpqlStatement.Select) new JpqlParser(jpql).parse();
        return tpcBuilder().buildScalarSelect(select);
    }

    @Test
    void rendersJoinedTypeEqualityAsDiscriminatorPredicateOverDerivedTable() {
        TranslatedSql t = joinedScalar("SELECT v.name FROM JVehicle v WHERE TYPE(v) = JCar");
        assertEquals(
                "select v.\"name\" as \"c0\" from (select * from (select \"j_vehicle\".\"id\" as \"id\", "
                        + "\"j_vehicle\".\"name\" as \"name\", \"j_car\".\"doors\" as \"doors\", "
                        + "\"j_truck\".\"payload\" as \"payload\", \"j_vehicle\".\"kind\" as \"kind\" "
                        + "from \"j_vehicle\" left join \"j_car\" on \"j_vehicle\".\"id\" = \"j_car\".\"id\" "
                        + "left join \"j_truck\" on \"j_vehicle\".\"id\" = \"j_truck\".\"id\") as \"nova_joined\") "
                        + "as v where v.\"kind\" = ?",
                t.sql());
        assertEquals(List.of(new JpqlBinding.Literal("CAR")), t.bindings());
    }

    @Test
    void rendersJoinedTreatProjectionWithAutomaticDiscriminatorOverDerivedTable() {
        TranslatedSql t = joinedScalar("SELECT TREAT(v AS JCar).doors FROM JVehicle v");
        assertTrue(t.sql().startsWith("select v.\"doors\" as \"c0\" from ("), t.sql());
        assertTrue(t.sql().endsWith(") as v where v.\"kind\" = ?"), t.sql());
        assertEquals(List.of(new JpqlBinding.Literal("CAR")), t.bindings());
    }

    @Test
    void rendersJoinedConcreteSubtypeRootWithAutomaticDiscriminatorOverDerivedTable() {
        TranslatedSql t = joinedScalar("SELECT c.doors FROM JCar c");
        assertTrue(t.sql().startsWith("select c.\"doors\" as \"c0\" from ("), t.sql());
        assertTrue(t.sql().endsWith(") as c where c.\"kind\" = ?"), t.sql());
        assertEquals(List.of(new JpqlBinding.Literal("CAR")), t.bindings());
    }

    @Test
    void rendersTablePerClassTypeEqualityAsDiscriminatorPredicateOverUnionDerivedTable() {
        TranslatedSql t = tpcScalar("SELECT v.name FROM TVehicle v WHERE TYPE(v) = TCar");
        assertTrue(t.sql().contains("union all"), t.sql());
        assertTrue(t.sql().contains("'CAR' as \"kind\""), t.sql());
        assertTrue(t.sql().contains("'TRUCK' as \"kind\""), t.sql());
        assertTrue(t.sql().endsWith(") as \"nova_tpc\") as v where v.\"kind\" = ?"), t.sql());
        assertEquals(List.of(new JpqlBinding.Literal("CAR")), t.bindings());
    }

    @Test
    void rendersTablePerClassTreatProjectionWithAutomaticDiscriminatorOverUnionDerivedTable() {
        TranslatedSql t = tpcScalar("SELECT TREAT(v AS TCar).doors FROM TVehicle v");
        assertTrue(t.sql().startsWith("select v.\"doors\" as \"c0\" from ("), t.sql());
        assertTrue(t.sql().endsWith(") as v where v.\"kind\" = ?"), t.sql());
        assertEquals(List.of(new JpqlBinding.Literal("CAR")), t.bindings());
    }

    @Test
    void failsFastOnAmbiguousJoinedColumnShadowedByEarlierSiblingSubtype() {
        // ShCar(discriminator "CAR")가 ShTruck(discriminator "TRUCK")보다 먼저 emit되므로, ShTruck.tag를
        // 가리키는 파생 테이블 컬럼은 실제로 ShCar.tag의 값이다 — silent wrong-column 대신 명확히 거부한다.
        JpqlEntityResolver r = new JpqlEntityResolver(
                metadataFactory, List.of(ShVehicle.class, ShCar.class, ShTruck.class));
        JpqlSqlBuilder shBuilder = new JpqlSqlBuilder(dialect, r);
        JpqlStatement.Select select = (JpqlStatement.Select) new JpqlParser(
                "SELECT TREAT(v AS ShTruck).tag FROM ShVehicle v").parse();
        JpqlException ex = assertThrows(JpqlException.class, () -> shBuilder.buildScalarSelect(select));
        assertTrue(ex.getMessage().contains("collides"), ex.getMessage());
    }

    @Test
    void failsFastOnAmbiguousJoinedColumnShadowedViaPlainPathOnConcreteSubtypeRoot() {
        // MAJOR: TREAT 경로뿐 아니라 plain 'alias.field'도 concrete-subtype-root(FROM ShTruck c)로 도달하면
        // 같은 dedupe 함정(ShCar.tag가 먼저 emit되어 derived 'tag' 컬럼을 차지)에 노출된다.
        JpqlEntityResolver r = new JpqlEntityResolver(
                metadataFactory, List.of(ShVehicle.class, ShCar.class, ShTruck.class));
        JpqlSqlBuilder shBuilder = new JpqlSqlBuilder(dialect, r);
        JpqlStatement.Select select = (JpqlStatement.Select) new JpqlParser(
                "SELECT c.tag FROM ShTruck c").parse();
        JpqlException ex = assertThrows(JpqlException.class, () -> shBuilder.buildScalarSelect(select));
        assertTrue(ex.getMessage().contains("collides"), ex.getMessage());
    }

    @Test
    void allowsTreatOnRootInheritedFieldWithoutFalseShadowRejection() {
        // MINOR: name은 JVehicle(root)이 선언한 컬럼이라, TREAT(v AS JCar).name으로 상속받아 참조해도
        // 실제로 그 값은 root에서 오는 게 정답이다 — root-collision으로 오인해 거부하면 안 된다.
        TranslatedSql t = joinedScalar("SELECT TREAT(v AS JCar).name FROM JVehicle v");
        assertTrue(t.sql().startsWith("select v.\"name\" as \"c0\" from ("), t.sql());
        assertTrue(t.sql().endsWith(") as v where v.\"kind\" = ?"), t.sql());
        assertEquals(List.of(new JpqlBinding.Literal("CAR")), t.bindings());
    }

    @Test
    void allowsPlainPathOnRootInheritedFieldFromConcreteSubtypeRootWithoutFalseShadowRejection() {
        // MINOR 대응 확인: plain 'alias.field' 경로도 root-inherited 필드는 거부하지 않아야 한다.
        TranslatedSql t = joinedScalar("SELECT c.name FROM JCar c");
        assertTrue(t.sql().startsWith("select c.\"name\" as \"c0\" from ("), t.sql());
        assertTrue(t.sql().endsWith(") as c where c.\"kind\" = ?"), t.sql());
        assertEquals(List.of(new JpqlBinding.Literal("CAR")), t.bindings());
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
    @Table(name = "j_vehicle")
    @Inheritance(strategy = InheritanceType.JOINED)
    @DiscriminatorColumn(name = "kind", discriminatorType = DiscriminatorType.STRING)
    public abstract static class JVehicle {
        @Id
        @Column(name = "id")
        private Long id;
        @Column(name = "name")
        private String name;
    }

    @Entity
    @Table(name = "j_car")
    @DiscriminatorValue("CAR")
    public static class JCar extends JVehicle {
        @Column(name = "doors")
        private int doors;
    }

    @Entity
    @Table(name = "j_truck")
    @DiscriminatorValue("TRUCK")
    public static class JTruck extends JVehicle {
        @Column(name = "payload")
        private double payload;
    }

    @Entity
    @Table(name = "t_vehicle")
    @Inheritance(strategy = InheritanceType.TABLE_PER_CLASS)
    @DiscriminatorColumn(name = "kind", discriminatorType = DiscriminatorType.STRING)
    public abstract static class TVehicle {
        @Id
        @Column(name = "id")
        private Long id;
        @Column(name = "name")
        private String name;
    }

    @Entity
    @Table(name = "t_car")
    @DiscriminatorValue("CAR")
    public static class TCar extends TVehicle {
        @Column(name = "doors")
        private int doors;
    }

    @Entity
    @Table(name = "t_truck")
    @DiscriminatorValue("TRUCK")
    public static class TTruck extends TVehicle {
        @Column(name = "payload")
        private double payload;
    }

    // 컬럼명 충돌(ambiguous derived-table column) fail-fast 전용 격리 fixture: ShCar/ShTruck가 같은
    // 컬럼명("tag")을 서로 다른 의미로 선언한다(discriminator 알파벳 순 CAR < TRUCK이라 ShCar가 먼저 emit됨).
    @Entity
    @Table(name = "sh_vehicle")
    @Inheritance(strategy = InheritanceType.JOINED)
    @DiscriminatorColumn(name = "kind", discriminatorType = DiscriminatorType.STRING)
    public abstract static class ShVehicle {
        @Id
        @Column(name = "id")
        private Long id;
        @Column(name = "name")
        private String name;
    }

    @Entity
    @Table(name = "sh_car")
    @DiscriminatorValue("CAR")
    public static class ShCar extends ShVehicle {
        @Column(name = "tag")
        private String tag;
    }

    @Entity
    @Table(name = "sh_truck")
    @DiscriminatorValue("TRUCK")
    public static class ShTruck extends ShVehicle {
        @Column(name = "tag")
        private String tag;
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
