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
    private final CriteriaSqlBuilder builder = new CriteriaSqlBuilder(dialect);
    private final CriteriaBuilder cb = new SimpleCriteriaBuilder(metamodel);

    private CriteriaSql scalar(CriteriaQuery<?> query) {
        return builder.build((CriteriaQueryImpl<?>) query);
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
    void failsFastOnUnknownAttribute() {
        CriteriaQuery<Object> cq = cb.createQuery(Object.class);
        Root<Employee> e = cq.from(Employee.class);
        assertThrows(CriteriaException.class, () -> e.get("nope"));
    }

    @Test
    void failsFastOnAssociationPath() {
        CriteriaQuery<Object> cq = cb.createQuery(Object.class);
        Root<Employee> e = cq.from(Employee.class);
        assertThrows(CriteriaException.class, () -> e.get("department"));
    }

    @Test
    void failsFastOnJoin() {
        CriteriaQuery<Object> cq = cb.createQuery(Object.class);
        Root<Employee> e = cq.from(Employee.class);
        assertThrows(CriteriaException.class, () -> e.join("department"));
    }

    @Test
    void failsFastOnColumnToColumnComparison() {
        CriteriaQuery<Object> cq = cb.createQuery(Object.class);
        Root<Employee> e = cq.from(Employee.class);
        // Object-rhs overload with an Expression value is rejected by the value guard.
        Object columnValue = e.get("id");
        CriteriaException ex = assertThrows(CriteriaException.class,
                () -> cb.equal(e.get("name"), columnValue));
        assertTrue(ex.getMessage().contains("column-to-column"));
        // The Expression-rhs overload is likewise rejected (unsupported stub).
        assertThrows(CriteriaException.class, () -> cb.equal(e.get("name"), e.get("id")));
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
            throw new UnsupportedOperationException("not needed for Criteria builder tests");
        }

        @Override
        public SchemaGenerator schemaGenerator() {
            throw new UnsupportedOperationException("not needed for Criteria builder tests");
        }
    }
}
