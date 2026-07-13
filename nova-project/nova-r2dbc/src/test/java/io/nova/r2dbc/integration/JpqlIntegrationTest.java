package io.nova.r2dbc.integration;

import io.nova.query.jpql.JpqlExecutor;
import io.nova.schema.SchemaInitializer;
import io.nova.schema.SimpleSchemaInitializer;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JPQL 서브시스템이 H2 in-memory R2DBC driver와 end-to-end로 동작하는지 검증한다 — 파싱 → SQL 변환 →
 * 리액티브 실행. 엔티티 조회/스칼라 투영/집계+GROUP BY(조인 포함)/벌크 UPDATE·DELETE 라운드트립을 확인한다.
 */
class JpqlIntegrationTest {

    private H2IntegrationTestSupport support;
    private JpqlExecutor jpql;

    @BeforeEach
    void setUp() {
        support = H2IntegrationTestSupport.create();
        SchemaInitializer schema =
                new SimpleSchemaInitializer(support.operations(), support.metadataFactory(), support.dialect());
        schema.create(Department.class).block();
        schema.create(Employee.class).block();
        jpql = new JpqlExecutor(support.operations(), support.dialect(), support.metadataFactory(),
                Employee.class, Department.class);

        Department engineering = support.operations().save(new Department("Engineering")).block();
        Department sales = support.operations().save(new Department("Sales")).block();
        support.operations().save(new Employee("Ada", new BigDecimal("150"), 40, engineering)).block();
        support.operations().save(new Employee("Bob", new BigDecimal("90"), 25, engineering)).block();
        support.operations().save(new Employee("Cara", new BigDecimal("120"), 35, sales)).block();
    }

    @Test
    void entitySelectWithParameterAndOrder() {
        StepVerifier.create(
                        jpql.createQuery("SELECT e FROM Employee e WHERE e.salary >= :min ORDER BY e.name", Employee.class)
                                .setParameter("min", new BigDecimal("100"))
                                .getResultList())
                .assertNext(e -> assertEquals("Ada", e.getName()))
                .assertNext(e -> assertEquals("Cara", e.getName()))
                .verifyComplete();
    }

    @Test
    void entitySelectPagination() {
        StepVerifier.create(
                        jpql.createQuery("SELECT e FROM Employee e ORDER BY e.name", Employee.class)
                                .setFirstResult(1)
                                .setMaxResults(1)
                                .getResultList())
                .assertNext(e -> assertEquals("Bob", e.getName()))
                .verifyComplete();
    }

    @Test
    void scalarProjectionOfSingleColumn() {
        StepVerifier.create(
                        jpql.createQuery("SELECT e.name FROM Employee e WHERE e.age >= :age ORDER BY e.name", String.class)
                                .setParameter("age", 35)
                                .getResultList())
                .expectNext("Ada", "Cara")
                .verifyComplete();
    }

    @Test
    void countAggregateAsSingleResult() {
        StepVerifier.create(
                        jpql.createQuery("SELECT COUNT(e) FROM Employee e", Object.class)
                                .getSingleResult())
                .assertNext(v -> assertEquals(3L, ((Number) v).longValue()))
                .verifyComplete();
    }

    @Test
    void groupByOverJoinReturnsObjectArrays() {
        StepVerifier.create(
                        jpql.createQuery(
                                        "SELECT d.name, COUNT(e) FROM Employee e JOIN e.department d "
                                                + "GROUP BY d.name ORDER BY d.name", Object.class)
                                .getResultList())
                .assertNext(row -> {
                    Object[] cols = (Object[]) row;
                    assertEquals("Engineering", cols[0]);
                    assertEquals(2L, ((Number) cols[1]).longValue());
                })
                .assertNext(row -> {
                    Object[] cols = (Object[]) row;
                    assertEquals("Sales", cols[0]);
                    assertEquals(1L, ((Number) cols[1]).longValue());
                })
                .verifyComplete();
    }

    @Test
    void bulkUpdateReturnsAffectedRowsAndPersists() {
        StepVerifier.create(
                        jpql.createQuery("UPDATE Employee e SET e.salary = e.salary + :raise WHERE e.age >= :age")
                                .setParameter("raise", new BigDecimal("10"))
                                .setParameter("age", 35)
                                .executeUpdate())
                .assertNext(affected -> assertEquals(2L, affected))
                .verifyComplete();

        // Ada(150+10=160)와 Cara(120+10=130)만 인상, Bob(90)은 유지.
        List<BigDecimal> salaries = jpql
                .createQuery("SELECT e.salary FROM Employee e ORDER BY e.name", BigDecimal.class)
                .getResultList()
                .collectList()
                .block();
        assertEquals(0, salaries.get(0).compareTo(new BigDecimal("160")));
        assertEquals(0, salaries.get(1).compareTo(new BigDecimal("90")));
        assertEquals(0, salaries.get(2).compareTo(new BigDecimal("130")));
    }

    @Test
    void bulkDeleteReturnsAffectedRows() {
        StepVerifier.create(
                        jpql.createQuery("DELETE FROM Employee e WHERE e.age < :age")
                                .setParameter("age", 30)
                                .executeUpdate())
                .assertNext(affected -> assertEquals(1L, affected))
                .verifyComplete();

        Long remaining = jpql.createQuery("SELECT COUNT(e) FROM Employee e", Object.class)
                .getSingleResult()
                .map(v -> ((Number) v).longValue())
                .block();
        assertEquals(2L, remaining);
    }

    @Test
    void missingParameterFailsFast() {
        StepVerifier.create(
                        jpql.createQuery("SELECT e.name FROM Employee e WHERE e.name = :n", String.class)
                                .getResultList())
                .verifyError();
    }

    @Test
    void getSingleResultOnMultipleRowsErrors() {
        StepVerifier.create(
                        jpql.createQuery("SELECT e.name FROM Employee e", String.class)
                                .getSingleResult())
                .verifyError();
    }

    // ------------------------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------------------------

    @Entity
    @Table(name = "jpql_department")
    public static class Department {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        @Column(name = "id")
        private Long id;
        @Column(name = "name")
        private String name;

        public Department() {
        }

        public Department(String name) {
            this.name = name;
        }

        public Long getId() {
            return id;
        }

        public String getName() {
            return name;
        }
    }

    @Entity
    @Table(name = "jpql_employee")
    public static class Employee {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
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

        public Employee() {
        }

        public Employee(String name, BigDecimal salary, int age, Department department) {
            this.name = name;
            this.salary = salary;
            this.age = age;
            this.department = department;
        }

        public Long getId() {
            return id;
        }

        public String getName() {
            return name;
        }

        public BigDecimal getSalary() {
            return salary;
        }

        public int getAge() {
            return age;
        }

        public Department getDepartment() {
            return department;
        }
    }
}
