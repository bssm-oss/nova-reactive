package io.nova.r2dbc.integration;

import io.nova.query.criteria.ReactiveCriteriaExecutor;
import io.nova.schema.SchemaInitializer;
import io.nova.schema.SimpleSchemaInitializer;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * JPA Criteria API가 H2 in-memory R2DBC driver와 end-to-end로 동작하는지 검증한다 — 타입세이프 조립 →
 * SQL/QuerySpec 변환 → 리액티브 실행. 엔티티 조회(술어/정렬/페이지), 스칼라 투영, 집계(count/avg,
 * GROUP BY), between/in/like/isNull 술어 라운드트립, 미지원 구성 fail-fast를 확인한다.
 */
class CriteriaIntegrationTest {

    private H2IntegrationTestSupport support;
    private ReactiveCriteriaExecutor criteria;

    @BeforeEach
    void setUp() {
        support = H2IntegrationTestSupport.create();
        SchemaInitializer schema =
                new SimpleSchemaInitializer(support.operations(), support.metadataFactory(), support.dialect());
        schema.create(Employee.class).block();
        criteria = new ReactiveCriteriaExecutor(support.operations(), support.dialect(), support.metadataFactory());

        support.operations().save(new Employee("Ada", new BigDecimal("150"), 40, "ace")).block();
        support.operations().save(new Employee("Bob", new BigDecimal("90"), 25, null)).block();
        support.operations().save(new Employee("Cara", new BigDecimal("120"), 35, null)).block();
        support.operations().save(new Employee("Dan", new BigDecimal("80"), 25, null)).block();
    }

    private CriteriaBuilder cb() {
        return criteria.getCriteriaBuilder();
    }

    @Test
    void entitySelectWithPredicateAndOrder() {
        CriteriaBuilder cb = cb();
        CriteriaQuery<Employee> cq = cb.createQuery(Employee.class);
        Root<Employee> e = cq.from(Employee.class);
        cq.select(e)
                .where(cb.ge(e.<BigDecimal>get("salary"), new BigDecimal("100")))
                .orderBy(cb.asc(e.<String>get("name")));

        StepVerifier.create(criteria.createQuery(cq).getResultList())
                .assertNext(x -> assertEquals("Ada", x.getName()))
                .assertNext(x -> assertEquals("Cara", x.getName()))
                .verifyComplete();
    }

    @Test
    void entitySelectPagination() {
        CriteriaBuilder cb = cb();
        CriteriaQuery<Employee> cq = cb.createQuery(Employee.class);
        Root<Employee> e = cq.from(Employee.class);
        cq.select(e).orderBy(cb.asc(e.<String>get("name")));

        StepVerifier.create(criteria.createQuery(cq).setFirstResult(1).setMaxResults(1).getResultList())
                .assertNext(x -> assertEquals("Bob", x.getName()))
                .verifyComplete();
    }

    @Test
    void betweenInAndLikePredicates() {
        CriteriaBuilder cb = cb();
        CriteriaQuery<Employee> between = cb.createQuery(Employee.class);
        Root<Employee> e1 = between.from(Employee.class);
        between.select(e1)
                .where(cb.between(e1.<Integer>get("age"), 30, 40))
                .orderBy(cb.asc(e1.<String>get("name")));
        StepVerifier.create(criteria.createQuery(between).getResultList())
                .assertNext(x -> assertEquals("Ada", x.getName()))
                .assertNext(x -> assertEquals("Cara", x.getName()))
                .verifyComplete();

        CriteriaBuilder cb2 = cb();
        CriteriaQuery<Employee> in = cb2.createQuery(Employee.class);
        Root<Employee> e2 = in.from(Employee.class);
        Predicate names = (Predicate) cb2.in(e2.<String>get("name")).value("Ada").value("Bob");
        in.select(e2).where(names).orderBy(cb2.asc(e2.<String>get("name")));
        StepVerifier.create(criteria.createQuery(in).getResultList())
                .assertNext(x -> assertEquals("Ada", x.getName()))
                .assertNext(x -> assertEquals("Bob", x.getName()))
                .verifyComplete();

        CriteriaBuilder cb3 = cb();
        CriteriaQuery<Employee> like = cb3.createQuery(Employee.class);
        Root<Employee> e3 = like.from(Employee.class);
        like.select(e3).where(cb3.like(e3.<String>get("name"), "A%"));
        StepVerifier.create(criteria.createQuery(like).getResultList())
                .assertNext(x -> assertEquals("Ada", x.getName()))
                .verifyComplete();
    }

    @Test
    void isNullPredicate() {
        CriteriaBuilder cb = cb();
        CriteriaQuery<Employee> cq = cb.createQuery(Employee.class);
        Root<Employee> e = cq.from(Employee.class);
        cq.select(e).where(cb.isNull(e.<String>get("nickname"))).orderBy(cb.asc(e.<String>get("name")));

        StepVerifier.create(criteria.createQuery(cq).getResultList())
                .assertNext(x -> assertEquals("Bob", x.getName()))
                .assertNext(x -> assertEquals("Cara", x.getName()))
                .assertNext(x -> assertEquals("Dan", x.getName()))
                .verifyComplete();
    }

    @Test
    void scalarProjectionOfSingleColumn() {
        CriteriaBuilder cb = cb();
        CriteriaQuery<String> cq = cb.createQuery(String.class);
        Root<Employee> e = cq.from(Employee.class);
        cq.select(e.<String>get("name"))
                .where(cb.ge(e.<Integer>get("age"), 35))
                .orderBy(cb.asc(e.<String>get("name")));

        StepVerifier.create(criteria.createQuery(cq).getResultList())
                .expectNext("Ada", "Cara")
                .verifyComplete();
    }

    @Test
    void countAggregateAsSingleResult() {
        CriteriaBuilder cb = cb();
        CriteriaQuery<Object> cq = cb.createQuery(Object.class);
        Root<Employee> e = cq.from(Employee.class);
        cq.select(cb.count(e));

        StepVerifier.create(criteria.createQuery(cq).getSingleResult())
                .assertNext(v -> assertEquals(4L, ((Number) v).longValue()))
                .verifyComplete();
    }

    @Test
    void avgAggregateAsSingleResult() {
        CriteriaBuilder cb = cb();
        CriteriaQuery<Object> cq = cb.createQuery(Object.class);
        Root<Employee> e = cq.from(Employee.class);
        cq.select(cb.avg(e.<BigDecimal>get("salary")));

        StepVerifier.create(criteria.createQuery(cq).getSingleResult())
                .assertNext(v -> assertEquals(110.0, ((Number) v).doubleValue()))
                .verifyComplete();
    }

    @Test
    void groupByReturnsObjectArrays() {
        CriteriaBuilder cb = cb();
        CriteriaQuery<Object> cq = cb.createQuery(Object.class);
        Root<Employee> e = cq.from(Employee.class);
        cq.multiselect(e.<Integer>get("age"), cb.count(e))
                .groupBy(e.<Integer>get("age"))
                .orderBy(cb.asc(e.<Integer>get("age")));

        StepVerifier.create(criteria.createQuery(cq).getResultList())
                .assertNext(row -> {
                    Object[] cols = (Object[]) row;
                    assertEquals(25, ((Number) cols[0]).intValue());
                    assertEquals(2L, ((Number) cols[1]).longValue());
                })
                .assertNext(row -> {
                    Object[] cols = (Object[]) row;
                    assertEquals(35, ((Number) cols[0]).intValue());
                    assertEquals(1L, ((Number) cols[1]).longValue());
                })
                .assertNext(row -> {
                    Object[] cols = (Object[]) row;
                    assertEquals(40, ((Number) cols[0]).intValue());
                    assertEquals(1L, ((Number) cols[1]).longValue());
                })
                .verifyComplete();
    }

    @Test
    void getSingleResultOnMultipleRowsErrors() {
        CriteriaBuilder cb = cb();
        CriteriaQuery<String> cq = cb.createQuery(String.class);
        Root<Employee> e = cq.from(Employee.class);
        cq.select(e.<String>get("name"));

        StepVerifier.create(criteria.createQuery(cq).getSingleResult())
                .verifyError();
    }

    @Test
    void selectingEntityAmongScalarsFailsFast() {
        CriteriaBuilder cb = cb();
        CriteriaQuery<Object> cq = cb.createQuery(Object.class);
        Root<Employee> e = cq.from(Employee.class);
        cq.multiselect(e, cb.count(e));

        StepVerifier.create(criteria.createQuery(cq).getResultList())
                .verifyError();
    }

    // ------------------------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------------------------

    @Entity
    @Table(name = "criteria_employee")
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
        @Column(name = "nickname")
        private String nickname;

        public Employee() {
        }

        public Employee(String name, BigDecimal salary, int age, String nickname) {
            this.name = name;
            this.salary = salary;
            this.age = age;
            this.nickname = nickname;
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

        public String getNickname() {
            return nickname;
        }
    }
}
