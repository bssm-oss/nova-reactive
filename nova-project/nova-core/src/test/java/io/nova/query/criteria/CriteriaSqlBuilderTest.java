package io.nova.query.criteria;

import io.nova.metadata.DefaultNamingStrategy;
import io.nova.metadata.EntityMetadataFactory;
import io.nova.sql.BindMarkerStrategy;
import io.nova.sql.Dialect;
import io.nova.sql.SchemaGenerator;
import io.nova.sql.SqlRenderer;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Criteria AST → 스칼라 SQL 변환기 단위 테스트. double-quote 식별자와 {@code ?} bind marker를 쓰는 테스트
 * dialect로 결정적 SQL을 검증한다. 엔티티/컬럼명은 @Table/@Column으로 고정하고, 단일 루트 unqualified
 * 컬럼 렌더링을 확인한다. 미지원 구성(조인/연관 경로/컬럼-대-컬럼 비교/미존재 필드)은 fail-fast를 검증한다.
 */
class CriteriaSqlBuilderTest {

    private final Dialect dialect = new TestDialect();
    private final EntityMetadataFactory metadataFactory = new EntityMetadataFactory(new DefaultNamingStrategy());
    private final CriteriaMetamodel metamodel = new CriteriaMetamodel(metadataFactory);
    private final CriteriaSqlBuilder builder = new CriteriaSqlBuilder(dialect, metadataFactory);
    private final AliasedCriteriaSqlBuilder aliasedBuilder = new AliasedCriteriaSqlBuilder(dialect);
    private final CriteriaBuilder cb = new SimpleCriteriaBuilder(metamodel);

    private CriteriaSql scalar(CriteriaQuery<?> query) {
        return builder.build((CriteriaQueryImpl<?>) query);
    }

    private CriteriaSql aliased(CriteriaQuery<?> query) {
        return aliasedBuilder.buildScalar((CriteriaQueryImpl<?>) query);
    }

    @Test
    void rendersScalarProjectionWithWhereAndOrder() {
        CriteriaQuery<Object> cq = cb.createQuery(Object.class);
        Root<Employee> e = cq.from(Employee.class);
        cq.multiselect(e.<String>get("name"))
                .where(cb.gt(e.<BigDecimal>get("salary"), new BigDecimal("100")))
                .orderBy(cb.desc(e.<String>get("name")));

        CriteriaSql t = scalar(cq);
        assertEquals("select \"name\" as \"c0\" from \"employee\" where \"salary\" > ? order by \"name\" desc", t.sql());
        assertEquals(1, t.selectionCount());
        assertEquals(1, t.bindings().size());
        assertEquals(0, new BigDecimal("100").compareTo((BigDecimal) t.bindings().get(0)));
    }

    @Test
    void rendersCountOverRootIdColumn() {
        CriteriaQuery<Object> cq = cb.createQuery(Object.class);
        Root<Employee> e = cq.from(Employee.class);
        cq.select(cb.count(e));

        CriteriaSql t = scalar(cq);
        assertEquals("select count(\"id\") as \"c0\" from \"employee\"", t.sql());
    }

    @Test
    void rendersAggregateWithGroupBy() {
        CriteriaQuery<Object> cq = cb.createQuery(Object.class);
        Root<Employee> e = cq.from(Employee.class);
        cq.multiselect(e.<Integer>get("age"), cb.avg(e.<BigDecimal>get("salary")))
                .groupBy(e.<Integer>get("age"));

        CriteriaSql t = scalar(cq);
        assertEquals("select \"age\" as \"c0\", avg(\"salary\") as \"c1\" from \"employee\" group by \"age\"", t.sql());
        assertEquals(2, t.selectionCount());
    }

    @Test
    void rendersNestedAndOrWithNullAndLike() {
        CriteriaQuery<Object> cq = cb.createQuery(Object.class);
        Root<Employee> e = cq.from(Employee.class);
        cq.multiselect(e.<Long>get("id"))
                .where(cb.and(
                        cb.ge(e.<BigDecimal>get("salary"), new BigDecimal("100")),
                        cb.or(cb.like(e.<String>get("name"), "A%"), cb.isNull(e.<String>get("name")))));

        CriteriaSql t = scalar(cq);
        assertEquals(
                "select \"id\" as \"c0\" from \"employee\" "
                        + "where (\"salary\" >= ? and (\"name\" like ? or \"name\" is null))",
                t.sql());
        assertEquals(2, t.bindings().size());
    }

    @Test
    void rendersNotBetweenAndIn() {
        CriteriaQuery<Object> cq = cb.createQuery(Object.class);
        Root<Employee> e = cq.from(Employee.class);
        cq.multiselect(e.<String>get("name"))
                .where(cb.not(cb.between(e.<Integer>get("age"), 20, 40)));
        assertEquals(
                "select \"name\" as \"c0\" from \"employee\" where not (\"age\" between ? and ?)",
                scalar(cq).sql());

        CriteriaQuery<Object> cq2 = cb.createQuery(Object.class);
        Root<Employee> e2 = cq2.from(Employee.class);
        Predicate in = (Predicate) cb.in(e2.<Long>get("id")).value(1L).value(2L);
        cq2.multiselect(e2.<String>get("name")).where(in);
        assertEquals(
                "select \"name\" as \"c0\" from \"employee\" where \"id\" in (?, ?)",
                scalar(cq2).sql());
    }

    @Test
    void emptyInRendersAlwaysFalse() {
        CriteriaQuery<Object> cq = cb.createQuery(Object.class);
        Root<Employee> e = cq.from(Employee.class);
        cq.multiselect(e.<String>get("name")).where((Predicate) cb.in(e.<Long>get("id")));
        assertEquals("select \"name\" as \"c0\" from \"employee\" where 1 = 0", scalar(cq).sql());
    }

    @Test
    void pathLevelEqualToNullBecomesIsNull() {
        CriteriaQuery<Object> cq = cb.createQuery(Object.class);
        Root<Employee> e = cq.from(Employee.class);
        cq.multiselect(e.<Long>get("id")).where((Predicate) e.<String>get("name").equalTo((Object) null));
        assertEquals("select \"id\" as \"c0\" from \"employee\" where \"name\" is null", scalar(cq).sql());
    }

    @Test
    void pathLevelNotEqualToNullBecomesIsNotNull() {
        CriteriaQuery<Object> cq = cb.createQuery(Object.class);
        Root<Employee> e = cq.from(Employee.class);
        cq.multiselect(e.<Long>get("id")).where((Predicate) e.<String>get("name").notEqualTo((Object) null));
        assertEquals("select \"id\" as \"c0\" from \"employee\" where \"name\" is not null", scalar(cq).sql());
    }

    @Test
    void pathLevelEqualToExpressionRejected() {
        CriteriaQuery<Object> cq = cb.createQuery(Object.class);
        Root<Employee> e = cq.from(Employee.class);
        Object columnValue = e.get("id");
        CriteriaException ex = assertThrows(CriteriaException.class,
                () -> e.<String>get("name").equalTo(columnValue));
        assertTrue(ex.getMessage().contains("column-to-column"));
    }

    @Test
    void pathLevelInRejectsExpressionAndNullElements() {
        CriteriaQuery<Object> cq = cb.createQuery(Object.class);
        Root<Employee> e = cq.from(Employee.class);
        Object columnValue = e.get("id");
        assertThrows(CriteriaException.class, () -> e.<Long>get("id").in(columnValue));
        assertThrows(CriteriaException.class, () -> e.<Long>get("id").in((Object) null));
    }

    @Test
    void failsFastOnUnknownAttribute() {
        CriteriaQuery<Object> cq = cb.createQuery(Object.class);
        Root<Employee> e = cq.from(Employee.class);
        assertThrows(CriteriaException.class, () -> e.get("nope"));
    }

    @Test
    void rendersInnerJoinWithQualifiedColumns() {
        CriteriaQuery<Object> cq = cb.createQuery(Object.class);
        Root<Employee> e = cq.from(Employee.class);
        jakarta.persistence.criteria.Join<Employee, Department> d = e.join("department");
        cq.multiselect(e.<String>get("name"))
                .where(cb.equal(d.<String>get("name"), "Sales"));

        assertEquals(
                "select \"t0\".\"name\" as \"c0\" from \"employee\" \"t0\" "
                        + "inner join \"department\" \"t1\" on \"t0\".\"dept_id\" = \"t1\".\"id\" "
                        + "where \"t1\".\"name\" = ?",
                aliased(cq).sql());
    }

    @Test
    void joinOverCompositeKeyToOneRendersAllOnComponents() {
        // 복합키 타겟 to-one은 다중컬럼 FK이므로 ON 조건이 모든 FK↔참조 @Id 컴포넌트 짝을 and로 잇는다.
        CriteriaQuery<Object> cq = cb.createQuery(Object.class);
        Root<io.nova.support.fixtures.FixtureEntities.CompositeJoinChild> c =
                cq.from(io.nova.support.fixtures.FixtureEntities.CompositeJoinChild.class);
        c.join("parent");
        cq.multiselect(c.<Long>get("id"));

        assertEquals(
                "select \"t0\".\"id\" as \"c0\" from \"gc_composite_child\" \"t0\" "
                        + "inner join \"gc_composite_parent\" \"t1\" "
                        + "on \"t0\".\"p_k1\" = \"t1\".\"k1\" and \"t0\".\"p_k2\" = \"t1\".\"k2\"",
                aliased(cq).sql());
    }

    @Test
    void terminalEqualityOverCompositeKeyToOneExpandsToComponentEquality() {
        // cb.equal(c.get("parent"), ref)은 각 FK 컴포넌트 eq의 and로 전개된다(참조 엔티티에서 컴포넌트 추출).
        CriteriaQuery<Object> cq = cb.createQuery(Object.class);
        Root<io.nova.support.fixtures.FixtureEntities.CompositeJoinChild> c =
                cq.from(io.nova.support.fixtures.FixtureEntities.CompositeJoinChild.class);
        io.nova.support.fixtures.FixtureEntities.CompositeJoinParent ref =
                new io.nova.support.fixtures.FixtureEntities.CompositeJoinParent();
        ref.setId(new io.nova.support.fixtures.FixtureEntities.CompositeJoinKey(5L, "x"));
        cq.multiselect(c.<Long>get("id")).where(cb.equal(c.get("parent"), ref));

        CriteriaSql t = aliased(cq);
        assertEquals(
                "select \"t0\".\"id\" as \"c0\" from \"gc_composite_child\" \"t0\" "
                        + "where (\"t0\".\"p_k1\" = ? and \"t0\".\"p_k2\" = ?)",
                t.sql());
        assertEquals(List.of(5L, "x"), t.bindings());
    }

    @Test
    void terminalNotEqualOverCompositeKeyToOneExpandsToComponentInequalityWithOr() {
        // cb.notEqual(c.get("parent"), ref)은 각 FK 컴포넌트 neq의 OR로 전개된다(튜플 부등; equal의 AND와 대비).
        CriteriaQuery<Object> cq = cb.createQuery(Object.class);
        Root<io.nova.support.fixtures.FixtureEntities.CompositeJoinChild> c =
                cq.from(io.nova.support.fixtures.FixtureEntities.CompositeJoinChild.class);
        io.nova.support.fixtures.FixtureEntities.CompositeJoinParent ref =
                new io.nova.support.fixtures.FixtureEntities.CompositeJoinParent();
        ref.setId(new io.nova.support.fixtures.FixtureEntities.CompositeJoinKey(5L, "x"));
        cq.multiselect(c.<Long>get("id")).where(cb.notEqual(c.get("parent"), ref));

        CriteriaSql t = aliased(cq);
        assertEquals(
                "select \"t0\".\"id\" as \"c0\" from \"gc_composite_child\" \"t0\" "
                        + "where (\"t0\".\"p_k1\" <> ? or \"t0\".\"p_k2\" <> ?)",
                t.sql());
        assertEquals(List.of(5L, "x"), t.bindings());
    }

    @Test
    void terminalIsNullOverCompositeKeyToOneExpandsToAllForeignKeyColumns() {
        CriteriaQuery<Object> cq = cb.createQuery(Object.class);
        Root<io.nova.support.fixtures.FixtureEntities.CompositeJoinChild> c =
                cq.from(io.nova.support.fixtures.FixtureEntities.CompositeJoinChild.class);
        cq.multiselect(c.<Long>get("id")).where(cb.isNull(c.get("parent")));

        CriteriaSql t = aliased(cq);
        assertEquals(
                "select \"t0\".\"id\" as \"c0\" from \"gc_composite_child\" \"t0\" "
                        + "where (\"t0\".\"p_k1\" is null and \"t0\".\"p_k2\" is null)",
                t.sql());
        assertTrue(t.bindings().isEmpty());
    }

    @Test
    void failsFastOnSelectingCompositeKeyToOneAsSingleColumn() {
        // SELECT c.parent(복합 to-one을 단일 컬럼으로 투영)은 축약 불가 → 조용한 오답 대신 fail-fast.
        CriteriaException ex = assertThrows(CriteriaException.class, () -> {
            CriteriaQuery<Object> cq = cb.createQuery(Object.class);
            Root<io.nova.support.fixtures.FixtureEntities.CompositeJoinChild> c =
                    cq.from(io.nova.support.fixtures.FixtureEntities.CompositeJoinChild.class);
            cq.multiselect(c.get("parent"));
            aliased(cq).sql();
        });
        assertTrue(ex.getMessage().contains("composite-key") || ex.getMessage().contains("single column"));
    }

    @Test
    void terminalInOverCompositeKeyToOneExpandsToOrOfAnds() {
        // cb.in(c.get("parent")).value(a).value(b) → 각 참조의 컴포넌트 동등(and)을 or로 잇는 OR-of-ANDs.
        CriteriaQuery<Object> cq = cb.createQuery(Object.class);
        Root<io.nova.support.fixtures.FixtureEntities.CompositeJoinChild> c =
                cq.from(io.nova.support.fixtures.FixtureEntities.CompositeJoinChild.class);
        io.nova.support.fixtures.FixtureEntities.CompositeJoinParent a =
                new io.nova.support.fixtures.FixtureEntities.CompositeJoinParent();
        a.setId(new io.nova.support.fixtures.FixtureEntities.CompositeJoinKey(5L, "x"));
        io.nova.support.fixtures.FixtureEntities.CompositeJoinParent b =
                new io.nova.support.fixtures.FixtureEntities.CompositeJoinParent();
        b.setId(new io.nova.support.fixtures.FixtureEntities.CompositeJoinKey(6L, "y"));
        cq.multiselect(c.<Long>get("id")).where(cb.in(c.get("parent")).value(a).value(b));

        CriteriaSql t = aliased(cq);
        assertEquals(
                "select \"t0\".\"id\" as \"c0\" from \"gc_composite_child\" \"t0\" where "
                        + "((\"t0\".\"p_k1\" = ? and \"t0\".\"p_k2\" = ?) or (\"t0\".\"p_k1\" = ? and \"t0\".\"p_k2\" = ?))",
                t.sql());
        assertEquals(List.of(5L, "x", 6L, "y"), t.bindings());
    }

    @Test
    void terminalOrderingOverCompositeKeyToOneExpandsLexicographically() {
        // 엔티티 참조는 Comparable이 아니어서 typed cb.lessThan으로는 만들 수 없으므로 술어를 직접 구성한다.
        CriteriaQuery<Object> cq = cb.createQuery(Object.class);
        Root<io.nova.support.fixtures.FixtureEntities.CompositeJoinChild> c =
                cq.from(io.nova.support.fixtures.FixtureEntities.CompositeJoinChild.class);
        io.nova.support.fixtures.FixtureEntities.CompositeJoinParent ref =
                new io.nova.support.fixtures.FixtureEntities.CompositeJoinParent();
        ref.setId(new io.nova.support.fixtures.FixtureEntities.CompositeJoinKey(5L, "x"));
        CriteriaColumnPath path = (CriteriaColumnPath) c.get("parent");
        cq.multiselect(c.<Long>get("id")).where(CriteriaPredicate.comparison(path, CompareOp.LT, ref));

        CriteriaSql t = aliased(cq);
        assertEquals(
                "select \"t0\".\"id\" as \"c0\" from \"gc_composite_child\" \"t0\" where "
                        + "((\"t0\".\"p_k1\" < ?) or (\"t0\".\"p_k1\" = ? and \"t0\".\"p_k2\" < ?))",
                t.sql());
        assertEquals(List.of(5L, 5L, "x"), t.bindings());
    }

    @Test
    void terminalBetweenOverCompositeKeyToOneExpandsToGreaterEqualAndLessEqual() {
        CriteriaQuery<Object> cq = cb.createQuery(Object.class);
        Root<io.nova.support.fixtures.FixtureEntities.CompositeJoinChild> c =
                cq.from(io.nova.support.fixtures.FixtureEntities.CompositeJoinChild.class);
        io.nova.support.fixtures.FixtureEntities.CompositeJoinParent lo =
                new io.nova.support.fixtures.FixtureEntities.CompositeJoinParent();
        lo.setId(new io.nova.support.fixtures.FixtureEntities.CompositeJoinKey(1L, "a"));
        io.nova.support.fixtures.FixtureEntities.CompositeJoinParent hi =
                new io.nova.support.fixtures.FixtureEntities.CompositeJoinParent();
        hi.setId(new io.nova.support.fixtures.FixtureEntities.CompositeJoinKey(9L, "z"));
        CriteriaColumnPath path = (CriteriaColumnPath) c.get("parent");
        cq.multiselect(c.<Long>get("id")).where(CriteriaPredicate.between(path, lo, hi));

        CriteriaSql t = aliased(cq);
        assertEquals(
                "select \"t0\".\"id\" as \"c0\" from \"gc_composite_child\" \"t0\" where "
                        + "(((\"t0\".\"p_k1\" > ?) or (\"t0\".\"p_k1\" = ? and \"t0\".\"p_k2\" >= ?)) and "
                        + "((\"t0\".\"p_k1\" < ?) or (\"t0\".\"p_k1\" = ? and \"t0\".\"p_k2\" <= ?)))",
                t.sql());
        assertEquals(List.of(1L, 1L, "a", 9L, 9L, "z"), t.bindings());
    }

    @Test
    void rendersLeftJoin() {
        CriteriaQuery<Object> cq = cb.createQuery(Object.class);
        Root<Employee> e = cq.from(Employee.class);
        jakarta.persistence.criteria.Join<Employee, Department> d =
                e.join("department", jakarta.persistence.criteria.JoinType.LEFT);
        cq.multiselect(e.<String>get("name"), d.<String>get("name"));

        assertEquals(
                "select \"t0\".\"name\" as \"c0\", \"t1\".\"name\" as \"c1\" from \"employee\" \"t0\" "
                        + "left join \"department\" \"t1\" on \"t0\".\"dept_id\" = \"t1\".\"id\"",
                aliased(cq).sql());
    }

    @Test
    void rendersMultiSegmentPathAsImplicitInnerJoin() {
        CriteriaQuery<Object> cq = cb.createQuery(Object.class);
        Root<Employee> e = cq.from(Employee.class);
        cq.multiselect(e.<Long>get("id"))
                .where(cb.equal(e.get("department").<String>get("name"), "Sales"))
                .orderBy(cb.asc(e.get("department").<String>get("name")));

        // 두 번의 department 탐색이 하나의 묵시적 INNER join(t1)을 공유해야 한다.
        assertEquals(
                "select \"t0\".\"id\" as \"c0\" from \"employee\" \"t0\" "
                        + "inner join \"department\" \"t1\" on \"t0\".\"dept_id\" = \"t1\".\"id\" "
                        + "where \"t1\".\"name\" = ? order by \"t1\".\"name\" asc",
                aliased(cq).sql());
    }

    @Test
    void rendersOneToManyJoinWithReversedOnOperands() {
        // @OneToMany(mappedBy)는 parentPK = childFK 순서(@ManyToOne의 반대)로 ON을 렌더해야 한다.
        CriteriaQuery<Object> cq = cb.createQuery(Object.class);
        Root<Department> d = cq.from(Department.class);
        jakarta.persistence.criteria.Join<Department, Employee> emps = d.join("employees");
        cq.multiselect(d.<String>get("name")).where(cb.gt(emps.<Integer>get("age"), 30));

        assertEquals(
                "select \"t0\".\"name\" as \"c0\" from \"department\" \"t0\" "
                        + "inner join \"employee\" \"t1\" on \"t0\".\"id\" = \"t1\".\"dept_id\" "
                        + "where \"t1\".\"age\" > ?",
                aliased(cq).sql());
    }

    @Test
    void rendersInverseOneToOneJoinResolvingFkByMappedBy() {
        // inverse @OneToOne은 mappedBy로 owning FK(Profile.user_id)를 지목하고 parentPK = childFK로 렌더한다.
        CriteriaQuery<Object> cq = cb.createQuery(Object.class);
        Root<User> u = cq.from(User.class);
        jakarta.persistence.criteria.Join<User, Profile> p = u.join("profile");
        cq.multiselect(u.<String>get("name")).where(cb.equal(p.<String>get("bio"), "x"));

        assertEquals(
                "select \"t0\".\"name\" as \"c0\" from \"app_user\" \"t0\" "
                        + "inner join \"profile\" \"t1\" on \"t0\".\"id\" = \"t1\".\"user_id\" "
                        + "where \"t1\".\"bio\" = ?",
                aliased(cq).sql());
    }

    @Test
    void rendersExistsSubquery() {
        CriteriaQuery<Object> cq = cb.createQuery(Object.class);
        Root<Employee> e = cq.from(Employee.class);
        jakarta.persistence.criteria.Subquery<Long> sub = cq.subquery(Long.class);
        Root<Department> d = sub.from(Department.class);
        sub.select(d.<Long>get("id")).where(cb.equal(d.<String>get("name"), "Sales"));
        cq.multiselect(e.<String>get("name")).where(cb.exists(sub));

        assertEquals(
                "select \"t0\".\"name\" as \"c0\" from \"employee\" \"t0\" "
                        + "where exists (select 1 from \"department\" \"t1\" where \"t1\".\"name\" = ?)",
                aliased(cq).sql());
    }

    @Test
    void rendersInSubquery() {
        CriteriaQuery<Object> cq = cb.createQuery(Object.class);
        Root<Employee> e = cq.from(Employee.class);
        jakarta.persistence.criteria.Subquery<Long> sub = cq.subquery(Long.class);
        Root<Department> d = sub.from(Department.class);
        sub.select(d.<Long>get("id")).where(cb.equal(d.<String>get("name"), "Sales"));
        cq.multiselect(e.<String>get("name")).where((Predicate) e.get("department").in(sub));

        assertEquals(
                "select \"t0\".\"name\" as \"c0\" from \"employee\" \"t0\" "
                        + "where \"t0\".\"dept_id\" in (select \"t1\".\"id\" from \"department\" \"t1\" "
                        + "where \"t1\".\"name\" = ?)",
                aliased(cq).sql());
    }

    @Test
    void rendersScalarSubqueryComparison() {
        CriteriaQuery<Object> cq = cb.createQuery(Object.class);
        Root<Employee> e = cq.from(Employee.class);
        jakarta.persistence.criteria.Subquery<Double> sub = cq.subquery(Double.class);
        Root<Employee> e2 = sub.from(Employee.class);
        sub.select(cb.avg(e2.<BigDecimal>get("salary")));
        cq.multiselect(e.<String>get("name")).where(cb.gt(e.<BigDecimal>get("salary"), sub));

        assertEquals(
                "select \"t0\".\"name\" as \"c0\" from \"employee\" \"t0\" "
                        + "where \"t0\".\"salary\" > (select avg(\"t1\".\"salary\") from \"employee\" \"t1\")",
                aliased(cq).sql());
    }

    @Test
    void failsFastOnManyToManyJoin() {
        CriteriaQuery<Object> cq = cb.createQuery(Object.class);
        Root<Employee> e = cq.from(Employee.class);
        // department는 지원되는 @ManyToOne이므로 여기선 미존재 연관 대신 RIGHT join 미지원을 확인한다.
        assertThrows(CriteriaException.class,
                () -> e.join("department", jakarta.persistence.criteria.JoinType.RIGHT));
    }

    @Test
    void failsFastOnScalarPathFurtherNavigation() {
        CriteriaQuery<Object> cq = cb.createQuery(Object.class);
        Root<Employee> e = cq.from(Employee.class);
        assertThrows(CriteriaException.class, () -> e.<String>get("name").get("length"));
    }

    @Test
    void objectRhsExpressionComparisonStillRejected() {
        CriteriaQuery<Object> cq = cb.createQuery(Object.class);
        Root<Employee> e = cq.from(Employee.class);
        // Object-rhs overload with an Expression value is still rejected by the value guard
        // (컬럼 대 컬럼은 Expression-rhs 오버로드로만 명시적으로 표현해야 한다).
        Object columnValue = e.get("id");
        CriteriaException ex = assertThrows(CriteriaException.class,
                () -> cb.equal(e.get("name"), columnValue));
        assertTrue(ex.getMessage().contains("column-to-column"));
    }

    @Test
    void rendersColumnToColumnComparisonWithAliases() {
        // Expression-rhs 오버로드의 컬럼 대 컬럼 비교는 이제 alias 한정 SQL로 렌더된다(상관 서브쿼리 등).
        CriteriaQuery<Object> cq = cb.createQuery(Object.class);
        Root<Employee> e = cq.from(Employee.class);
        cq.multiselect(e.<String>get("name")).where(cb.equal(e.get("name"), e.get("id")));
        assertEquals(
                "select \"t0\".\"name\" as \"c0\" from \"employee\" \"t0\" where \"t0\".\"name\" = \"t0\".\"id\"",
                aliased(cq).sql());
    }

    @Test
    void failsFastOnMultipleRoots() {
        CriteriaQuery<Object> cq = cb.createQuery(Object.class);
        cq.from(Employee.class);
        assertThrows(CriteriaException.class, () -> cq.from(Department.class));
    }

    @Test
    void failsFastOnUnsupportedBuilderMethod() {
        assertThrows(CriteriaException.class, () -> cb.currentTimestamp());
    }

    // ------------------------------------------------------------------------------------
    // TYPE() / TREAT() polymorphism
    // ------------------------------------------------------------------------------------

    @Test
    void rendersTypeEqualityAsDiscriminatorPredicate() {
        CriteriaQuery<Object> cq = cb.createQuery(Object.class);
        Root<Vehicle> v = cq.from(Vehicle.class);
        cq.multiselect(v.<String>get("name")).where(cb.equal(v.type(), Car.class));

        CriteriaSql t = scalar(cq);
        assertEquals("select \"name\" as \"c0\" from \"vehicle\" where \"kind\" = ?", t.sql());
        assertEquals(List.of("CAR"), t.bindings());
    }

    @Test
    void rendersTreatProjectionWithDiscriminatorFilter() {
        CriteriaQuery<Object> cq = cb.createQuery(Object.class);
        Root<Vehicle> v = cq.from(Vehicle.class);
        Root<Car> car = cb.treat(v, Car.class);
        cq.multiselect(car.<Integer>get("doors"));

        CriteriaSql t = scalar(cq);
        assertEquals("select \"doors\" as \"c0\" from \"vehicle\" where \"kind\" = ?", t.sql());
        assertEquals(List.of("CAR"), t.bindings());
    }

    @Test
    void failsFastOnTypeForNonInheritanceEntity() {
        CriteriaQuery<Object> cq = cb.createQuery(Object.class);
        Root<Employee> e = cq.from(Employee.class);
        assertThrows(CriteriaException.class, () -> cb.equal(e.type(), Employee.class));
    }

    // ------------------------------------------------------------------------------------
    // TYPE() / TREAT() polymorphism over JOINED / TABLE_PER_CLASS inheritance
    // ------------------------------------------------------------------------------------

    @Test
    void rendersJoinedTypeEqualityAsUnqualifiedDiscriminatorPredicateOverDerivedTable() {
        metamodel.resolve(JCar.class);
        metamodel.resolve(JTruck.class);
        CriteriaQuery<Object> cq = cb.createQuery(Object.class);
        Root<JVehicle> v = cq.from(JVehicle.class);
        cq.multiselect(v.<String>get("name")).where(cb.equal(v.type(), JCar.class));

        CriteriaSql t = scalar(cq);
        assertTrue(t.sql().contains("left join \"j_car\""), t.sql());
        assertTrue(t.sql().contains("left join \"j_truck\""), t.sql());
        assertTrue(t.sql().endsWith(") as nova_criteria_root where \"kind\" = ?"), t.sql());
        assertEquals(List.of("CAR"), t.bindings());
    }

    @Test
    void rendersJoinedTreatProjectionWithUnqualifiedDiscriminatorFilter() {
        metamodel.resolve(JCar.class);
        metamodel.resolve(JTruck.class);
        CriteriaQuery<Object> cq = cb.createQuery(Object.class);
        Root<JVehicle> v = cq.from(JVehicle.class);
        Root<JCar> car = cb.treat(v, JCar.class);
        cq.multiselect(car.<Integer>get("doors"));

        CriteriaSql t = scalar(cq);
        assertTrue(t.sql().startsWith("select \"doors\" as \"c0\" from ("), t.sql());
        assertTrue(t.sql().endsWith(") as nova_criteria_root where \"kind\" = ?"), t.sql());
        assertEquals(List.of("CAR"), t.bindings());
    }

    @Test
    void rendersTablePerClassTypeEqualityAsUnqualifiedDiscriminatorPredicateOverUnion() {
        metamodel.resolve(TCar.class);
        metamodel.resolve(TTruck.class);
        CriteriaQuery<Object> cq = cb.createQuery(Object.class);
        Root<TVehicle> v = cq.from(TVehicle.class);
        cq.multiselect(v.<String>get("name")).where(cb.equal(v.type(), TCar.class));

        CriteriaSql t = scalar(cq);
        assertTrue(t.sql().contains("union all"), t.sql());
        assertTrue(t.sql().contains("'CAR' as \"kind\""), t.sql());
        assertTrue(t.sql().contains("'TRUCK' as \"kind\""), t.sql());
        assertTrue(t.sql().endsWith(") as nova_criteria_root where \"kind\" = ?"), t.sql());
        assertEquals(List.of("CAR"), t.bindings());
    }

    @Test
    void rendersTablePerClassTreatProjectionWithUnqualifiedDiscriminatorFilter() {
        metamodel.resolve(TCar.class);
        metamodel.resolve(TTruck.class);
        CriteriaQuery<Object> cq = cb.createQuery(Object.class);
        Root<TVehicle> v = cq.from(TVehicle.class);
        Root<TCar> car = cb.treat(v, TCar.class);
        cq.multiselect(car.<Integer>get("doors"));

        CriteriaSql t = scalar(cq);
        assertTrue(t.sql().startsWith("select \"doors\" as \"c0\" from ("), t.sql());
        assertTrue(t.sql().endsWith(") as nova_criteria_root where \"kind\" = ?"), t.sql());
        assertEquals(List.of("CAR"), t.bindings());
    }

    // ------------------------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------------------------

    @Entity
    @Table(name = "vehicle")
    @jakarta.persistence.Inheritance(strategy = jakarta.persistence.InheritanceType.SINGLE_TABLE)
    @jakarta.persistence.DiscriminatorColumn(
            name = "kind", discriminatorType = jakarta.persistence.DiscriminatorType.STRING)
    public abstract static class Vehicle {
        @Id
        @Column(name = "id")
        private Long id;
        @Column(name = "name")
        private String name;
    }

    @Entity
    @jakarta.persistence.DiscriminatorValue("CAR")
    public static class Car extends Vehicle {
        @Column(name = "doors")
        private int doors;
    }

    @Entity
    @Table(name = "j_vehicle")
    @jakarta.persistence.Inheritance(strategy = jakarta.persistence.InheritanceType.JOINED)
    @jakarta.persistence.DiscriminatorColumn(
            name = "kind", discriminatorType = jakarta.persistence.DiscriminatorType.STRING)
    public abstract static class JVehicle {
        @Id
        @Column(name = "id")
        private Long id;
        @Column(name = "name")
        private String name;
    }

    @Entity
    @Table(name = "j_car")
    @jakarta.persistence.DiscriminatorValue("CAR")
    public static class JCar extends JVehicle {
        @Column(name = "doors")
        private int doors;
    }

    @Entity
    @Table(name = "j_truck")
    @jakarta.persistence.DiscriminatorValue("TRUCK")
    public static class JTruck extends JVehicle {
        @Column(name = "payload")
        private double payload;
    }

    @Entity
    @Table(name = "t_vehicle")
    @jakarta.persistence.Inheritance(strategy = jakarta.persistence.InheritanceType.TABLE_PER_CLASS)
    @jakarta.persistence.DiscriminatorColumn(
            name = "kind", discriminatorType = jakarta.persistence.DiscriminatorType.STRING)
    public abstract static class TVehicle {
        @Id
        @Column(name = "id")
        private Long id;
        @Column(name = "name")
        private String name;
    }

    @Entity
    @Table(name = "t_car")
    @jakarta.persistence.DiscriminatorValue("CAR")
    public static class TCar extends TVehicle {
        @Column(name = "doors")
        private int doors;
    }

    @Entity
    @Table(name = "t_truck")
    @jakarta.persistence.DiscriminatorValue("TRUCK")
    public static class TTruck extends TVehicle {
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
        @OneToMany(targetEntity = Employee.class, mappedBy = "department")
        private List<Employee> employees;
    }

    @Entity
    @Table(name = "app_user")
    public static class User {
        @Id
        @Column(name = "id")
        private Long id;
        @Column(name = "name")
        private String name;
        // inverse @OneToOne — 컬럼 없는 마커, owning FK는 Profile.user(@JoinColumn user_id)에 있다.
        @OneToOne(targetEntity = Profile.class, mappedBy = "user")
        private Profile profile;
    }

    @Entity
    @Table(name = "profile")
    public static class Profile {
        @Id
        @Column(name = "id")
        private Long id;
        @Column(name = "bio")
        private String bio;
        @OneToOne(targetEntity = User.class)
        @JoinColumn(name = "user_id")
        private User user;
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
            throw new UnsupportedOperationException("not needed for Criteria builder tests");
        }
    }
}
