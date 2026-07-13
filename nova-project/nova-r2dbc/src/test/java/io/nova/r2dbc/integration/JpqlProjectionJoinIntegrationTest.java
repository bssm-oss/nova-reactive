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
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * JPQL 파리티 Batch B(프로젝션·조인 심화)의 H2 end-to-end 라운드트립. SELECT NEW DTO 프로젝션, 다세그먼트
 * 경로 묵시 조인, 함수 확장(CONCAT/LENGTH/CAST/LOCATE/SIZE), 필터용 non-fetch JOIN 엔티티 반환을 검증한다.
 */
class JpqlProjectionJoinIntegrationTest {

    private H2IntegrationTestSupport support;
    private JpqlExecutor jpql;

    @BeforeEach
    void setUp() {
        support = H2IntegrationTestSupport.create();
        SchemaInitializer schema =
                new SimpleSchemaInitializer(support.operations(), support.metadataFactory(), support.dialect());
        schema.create(Company.class).block();
        schema.create(Department.class).block();
        schema.create(Employee.class).block();
        jpql = new JpqlExecutor(support.operations(), support.dialect(), support.metadataFactory(),
                Employee.class, Department.class, Company.class);

        Company acme = support.operations().save(new Company("Acme")).block();
        Company globex = support.operations().save(new Company("Globex")).block();
        Department engineering = support.operations().save(new Department("Engineering", acme)).block();
        Department sales = support.operations().save(new Department("Sales", globex)).block();
        support.operations().save(new Employee("Ada", 40, engineering)).block();
        support.operations().save(new Employee("Bob", 25, engineering)).block();
        support.operations().save(new Employee("Cara", 35, sales)).block();
    }

    @Test
    void selectNewDtoProjection() {
        String jpqlText = "SELECT NEW " + EmployeeSummary.class.getName()
                + "(e.name, e.age) FROM Employee e ORDER BY e.name";
        StepVerifier.create(jpql.createQuery(jpqlText, EmployeeSummary.class).getResultList())
                .assertNext(s -> {
                    assertEquals("Ada", s.name());
                    assertEquals(40, s.age());
                })
                .assertNext(s -> assertEquals("Bob", s.name()))
                .assertNext(s -> assertEquals("Cara", s.name()))
                .verifyComplete();
    }

    @Test
    void selectNewDtoWithAggregateOverJoin() {
        String jpqlText = "SELECT NEW " + DeptHeadcount.class.getName()
                + "(d.name, COUNT(e)) FROM Employee e JOIN e.department d GROUP BY d.name ORDER BY d.name";
        StepVerifier.create(jpql.createQuery(jpqlText, DeptHeadcount.class).getResultList())
                .assertNext(h -> {
                    assertEquals("Engineering", h.department());
                    assertEquals(2L, h.headcount());
                })
                .assertNext(h -> {
                    assertEquals("Sales", h.department());
                    assertEquals(1L, h.headcount());
                })
                .verifyComplete();
    }

    @Test
    void multiSegmentPathImplicitJoinInWhere() {
        StepVerifier.create(
                        jpql.createQuery("SELECT e.name FROM Employee e WHERE e.department.name = :dn ORDER BY e.name",
                                        String.class)
                                .setParameter("dn", "Engineering")
                                .getResultList())
                .expectNext("Ada", "Bob")
                .verifyComplete();
    }

    @Test
    void deepMultiSegmentPathThroughTwoRelations() {
        StepVerifier.create(
                        jpql.createQuery(
                                        "SELECT e.name FROM Employee e WHERE e.department.company.name = :cn ORDER BY e.name",
                                        String.class)
                                .setParameter("cn", "Globex")
                                .getResultList())
                .expectNext("Cara")
                .verifyComplete();
    }

    @Test
    void functionExpansionConcatLengthCastLocate() {
        StepVerifier.create(
                        jpql.createQuery("SELECT CONCAT(e.name, '!') FROM Employee e WHERE e.name = 'Ada'", String.class)
                                .getResultList())
                .expectNext("Ada!")
                .verifyComplete();

        StepVerifier.create(
                        jpql.createQuery("SELECT LENGTH(e.name) FROM Employee e WHERE e.name = 'Cara'", Object.class)
                                .getSingleResult())
                .assertNext(v -> assertEquals(4L, ((Number) v).longValue()))
                .verifyComplete();

        StepVerifier.create(
                        jpql.createQuery("SELECT CAST(e.age AS string) FROM Employee e WHERE e.name = 'Ada'", String.class)
                                .getSingleResult())
                .assertNext(v -> assertEquals("40", v))
                .verifyComplete();

        StepVerifier.create(
                        jpql.createQuery("SELECT LOCATE('r', e.name) FROM Employee e WHERE e.name = 'Cara'", Object.class)
                                .getSingleResult())
                .assertNext(v -> assertEquals(3L, ((Number) v).longValue()))
                .verifyComplete();
    }

    @Test
    void sizeOfOneToManyCollection() {
        StepVerifier.create(
                        jpql.createQuery("SELECT SIZE(d.employees) FROM Department d WHERE d.name = :dn", Object.class)
                                .setParameter("dn", "Engineering")
                                .getSingleResult())
                .assertNext(v -> assertEquals(2L, ((Number) v).longValue()))
                .verifyComplete();
    }

    @Test
    void entityReturnWithFilteringNonFetchJoin() {
        StepVerifier.create(
                        jpql.createQuery(
                                        "SELECT e FROM Employee e JOIN e.department d WHERE d.name = :dn ORDER BY e.name",
                                        Employee.class)
                                .setParameter("dn", "Engineering")
                                .getResultList())
                .assertNext(e -> assertEquals("Ada", e.getName()))
                .assertNext(e -> assertEquals("Bob", e.getName()))
                .verifyComplete();
    }

    @Test
    void entityReturnWithFilteringJoinAndPagination() {
        List<String> names = jpql.createQuery(
                        "SELECT e FROM Employee e JOIN e.department d WHERE d.company.name = :cn ORDER BY e.name",
                        Employee.class)
                .setParameter("cn", "Acme")
                .setMaxResults(1)
                .getResultList()
                .map(Employee::getName)
                .collectList()
                .block();
        assertEquals(List.of("Ada"), names);
    }

    // ------------------------------------------------------------------------------------
    // DTOs
    // ------------------------------------------------------------------------------------

    public record EmployeeSummary(String name, int age) {
    }

    public record DeptHeadcount(String department, long headcount) {
    }

    // ------------------------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------------------------

    @Entity
    @Table(name = "bpjj_company")
    public static class Company {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        @Column(name = "id")
        private Long id;
        @Column(name = "name")
        private String name;

        public Company() {
        }

        public Company(String name) {
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
    @Table(name = "bpjj_department")
    public static class Department {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        @Column(name = "id")
        private Long id;
        @Column(name = "name")
        private String name;
        @ManyToOne
        @JoinColumn(name = "company_id")
        private Company company;
        @OneToMany(mappedBy = "department")
        private List<Employee> employees;

        public Department() {
        }

        public Department(String name, Company company) {
            this.name = name;
            this.company = company;
        }

        public Long getId() {
            return id;
        }

        public String getName() {
            return name;
        }
    }

    @Entity
    @Table(name = "bpjj_employee")
    public static class Employee {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        @Column(name = "id")
        private Long id;
        @Column(name = "name")
        private String name;
        @Column(name = "age")
        private int age;
        @ManyToOne
        @JoinColumn(name = "dept_id")
        private Department department;

        public Employee() {
        }

        public Employee(String name, int age, Department department) {
            this.name = name;
            this.age = age;
            this.department = department;
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
}
