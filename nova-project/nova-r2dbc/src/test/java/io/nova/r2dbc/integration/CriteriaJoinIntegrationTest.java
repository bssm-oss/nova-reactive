package io.nova.r2dbc.integration;

import io.nova.query.criteria.ReactiveCriteriaExecutor;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Criteria join / subquery / 다세그먼트 경로가 H2 in-memory R2DBC driver와 end-to-end로 동작하는지 검증한다.
 * SQL string unit test만으로는 driver 수용성을 보장할 수 없다는 회귀 메모리에 따라(cycle 8 F4) 실제 라운드
 * 트립으로 보호한다: inner/left join 결과, 묵시적 조인(다세그먼트 경로), 상관 EXISTS/IN 서브쿼리, 스칼라
 * 서브쿼리 비교, fetch join 수용을 확인한다.
 */
class CriteriaJoinIntegrationTest {

    private H2IntegrationTestSupport support;
    private ReactiveCriteriaExecutor criteria;

    @BeforeEach
    void setUp() {
        support = H2IntegrationTestSupport.create();
        support.execute("create table \"cj_department\" ("
                + "\"id\" bigint primary key, "
                + "\"name\" varchar(255), "
                + "\"location\" varchar(255))");
        support.execute("create table \"cj_employee\" ("
                + "\"id\" bigint primary key, "
                + "\"name\" varchar(255), "
                + "\"age\" int, "
                + "\"dept_id\" bigint)");
        support.execute("insert into \"cj_department\" (\"id\", \"name\", \"location\") values (1, 'Sales', 'HQ')");
        support.execute("insert into \"cj_department\" (\"id\", \"name\", \"location\") values (2, 'Eng', 'Remote')");
        support.execute("insert into \"cj_employee\" (\"id\", \"name\", \"age\", \"dept_id\") values (1, 'Ada', 40, 1)");
        support.execute("insert into \"cj_employee\" (\"id\", \"name\", \"age\", \"dept_id\") values (2, 'Bob', 25, 2)");
        support.execute("insert into \"cj_employee\" (\"id\", \"name\", \"age\", \"dept_id\") values (3, 'Cara', 35, 1)");
        support.execute("insert into \"cj_employee\" (\"id\", \"name\", \"age\", \"dept_id\") values (4, 'Dan', 30, null)");
        criteria = new ReactiveCriteriaExecutor(support.operations(), support.dialect(), support.metadataFactory());
    }

    private CriteriaBuilder cb() {
        return criteria.getCriteriaBuilder();
    }

    @Test
    void innerJoinEntityReturn() {
        CriteriaBuilder cb = cb();
        CriteriaQuery<Employee> cq = cb.createQuery(Employee.class);
        Root<Employee> e = cq.from(Employee.class);
        Join<Employee, Department> d = e.join("department");
        cq.select(e).where(cb.equal(d.<String>get("name"), "Sales")).orderBy(cb.asc(e.<String>get("name")));

        StepVerifier.create(criteria.createQuery(cq).getResultList())
                .assertNext(x -> assertEquals("Ada", x.getName()))
                .assertNext(x -> assertEquals("Cara", x.getName()))
                .verifyComplete();
    }

    @Test
    void innerJoinScalarProjection() {
        CriteriaBuilder cb = cb();
        CriteriaQuery<String> cq = cb.createQuery(String.class);
        Root<Employee> e = cq.from(Employee.class);
        Join<Employee, Department> d = e.join("department");
        cq.select(e.<String>get("name")).where(cb.equal(d.<String>get("name"), "Sales"))
                .orderBy(cb.asc(e.<String>get("name")));

        StepVerifier.create(criteria.createQuery(cq).getResultList())
                .expectNext("Ada", "Cara")
                .verifyComplete();
    }

    @Test
    void leftJoinExposesUnmatchedRows() {
        CriteriaBuilder cb = cb();
        CriteriaQuery<String> cq = cb.createQuery(String.class);
        Root<Employee> e = cq.from(Employee.class);
        Join<Employee, Department> d = e.join("department", JoinType.LEFT);
        // department가 없는(dept_id null) 직원은 left join으로 남고 d.id is null로 걸러진다.
        cq.select(e.<String>get("name")).where(cb.isNull(d.<Long>get("id")));

        StepVerifier.create(criteria.createQuery(cq).getResultList())
                .expectNext("Dan")
                .verifyComplete();
    }

    @Test
    void multiSegmentPathImplicitJoin() {
        CriteriaBuilder cb = cb();
        CriteriaQuery<Employee> cq = cb.createQuery(Employee.class);
        Root<Employee> e = cq.from(Employee.class);
        cq.select(e).where(cb.equal(e.get("department").<String>get("name"), "Eng"));

        StepVerifier.create(criteria.createQuery(cq).getResultList())
                .assertNext(x -> assertEquals("Bob", x.getName()))
                .verifyComplete();
    }

    @Test
    void correlatedExistsSubqueryEntityReturn() {
        CriteriaBuilder cb = cb();
        CriteriaQuery<Employee> cq = cb.createQuery(Employee.class);
        Root<Employee> e = cq.from(Employee.class);
        Subquery<Long> sub = cq.subquery(Long.class);
        Root<Department> d = sub.from(Department.class);
        Root<Employee> corr = sub.correlate(e);
        sub.select(d.<Long>get("id")).where(cb.and(
                cb.equal(d.<Long>get("id"), corr.get("department")),
                cb.equal(d.<String>get("name"), "Sales")));
        cq.select(e).where(cb.exists(sub)).orderBy(cb.asc(e.<String>get("name")));

        StepVerifier.create(criteria.createQuery(cq).getResultList())
                .assertNext(x -> assertEquals("Ada", x.getName()))
                .assertNext(x -> assertEquals("Cara", x.getName()))
                .verifyComplete();
    }

    @Test
    void inSubqueryOnForeignKey() {
        CriteriaBuilder cb = cb();
        CriteriaQuery<String> cq = cb.createQuery(String.class);
        Root<Employee> e = cq.from(Employee.class);
        Subquery<Long> sub = cq.subquery(Long.class);
        Root<Department> d = sub.from(Department.class);
        sub.select(d.<Long>get("id")).where(cb.equal(d.<String>get("location"), "HQ"));
        cq.select(e.<String>get("name")).where((Predicate) e.get("department").in(sub))
                .orderBy(cb.asc(e.<String>get("name")));

        StepVerifier.create(criteria.createQuery(cq).getResultList())
                .expectNext("Ada", "Cara")
                .verifyComplete();
    }

    @Test
    void scalarSubqueryComparison() {
        CriteriaBuilder cb = cb();
        CriteriaQuery<String> cq = cb.createQuery(String.class);
        Root<Employee> e = cq.from(Employee.class);
        Subquery<Double> sub = cq.subquery(Double.class);
        Root<Employee> e2 = sub.from(Employee.class);
        sub.select(cb.avg(e2.<Integer>get("age")));
        // 평균 나이(32.5)보다 많은 직원: Ada(40), Cara(35).
        cq.select(e.<String>get("name")).where(cb.gt(e.<Integer>get("age"), sub))
                .orderBy(cb.asc(e.<String>get("name")));

        StepVerifier.create(criteria.createQuery(cq).getResultList())
                .expectNext("Ada", "Cara")
                .verifyComplete();
    }

    @Test
    void fetchJoinIsAcceptedAndAssociationIsHydrated() {
        CriteriaBuilder cb = cb();
        CriteriaQuery<Employee> cq = cb.createQuery(Employee.class);
        Root<Employee> e = cq.from(Employee.class);
        e.fetch("department");
        cq.select(e).where(cb.equal(e.<String>get("name"), "Ada"));

        StepVerifier.create(criteria.createQuery(cq).getResultList())
                .assertNext(x -> {
                    assertEquals("Ada", x.getName());
                    assertNotNull(x.getDepartment(), "always-eager 모델에서 department는 hydrate되어야 한다");
                    assertEquals(1L, x.getDepartment().getId());
                })
                .verifyComplete();
    }

    // ------------------------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------------------------

    @Entity
    @Table(name = "cj_employee")
    public static class Employee {
        @Id
        @Column(name = "id")
        private Long id;
        @Column(name = "name")
        private String name;
        @Column(name = "age")
        private int age;
        @ManyToOne(targetEntity = Department.class)
        @JoinColumn(name = "dept_id")
        private Department department;

        public Employee() {
        }

        public Long getId() {
            return id;
        }

        public String getName() {
            return name;
        }

        public int getAge() {
            return age;
        }

        public Department getDepartment() {
            return department;
        }
    }

    @Entity
    @Table(name = "cj_department")
    public static class Department {
        @Id
        @Column(name = "id")
        private Long id;
        @Column(name = "name")
        private String name;
        @Column(name = "location")
        private String location;

        public Department() {
        }

        public Long getId() {
            return id;
        }

        public String getName() {
            return name;
        }

        public String getLocation() {
            return location;
        }
    }
}
