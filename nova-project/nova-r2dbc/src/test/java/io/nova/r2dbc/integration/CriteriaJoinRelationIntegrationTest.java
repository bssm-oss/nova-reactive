package io.nova.r2dbc.integration;

import io.nova.query.criteria.ReactiveCriteriaExecutor;
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
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Root;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * to-many / inverse 관계 조인의 H2 라운드트립을 검증한다. owning @ManyToOne(operand 순서 {@code fk = pk})만
 * 돌던 {@link CriteriaJoinIntegrationTest}를 보완해, 반대 operand 순서({@code parentPK = childFK})인
 * @OneToMany(mappedBy)/inverse @OneToOne JOIN-ON을 실드라이버로 확인하고, 무엇보다 to-many 조인으로 생긴
 * 카티전 중복 부모 행을 2단계 실행이 <b>정확히 중복 제거하고 순서를 보존</b>하는지(엔티티 반환 경로의 핵심
 * 정확성 주장) 검증한다.
 */
class CriteriaJoinRelationIntegrationTest {

    private H2IntegrationTestSupport support;
    private ReactiveCriteriaExecutor criteria;

    @BeforeEach
    void setUp() {
        support = H2IntegrationTestSupport.create();
        support.execute("create table \"cjr_department\" (\"id\" bigint primary key, \"name\" varchar(255))");
        support.execute("create table \"cjr_employee\" ("
                + "\"id\" bigint primary key, \"name\" varchar(255), \"age\" int, \"dept_id\" bigint)");
        support.execute("create table \"cjr_user\" (\"id\" bigint primary key, \"name\" varchar(255))");
        support.execute("create table \"cjr_profile\" ("
                + "\"id\" bigint primary key, \"bio\" varchar(255), \"user_id\" bigint)");

        support.execute("insert into \"cjr_department\" (\"id\", \"name\") values (1, 'Sales')");
        support.execute("insert into \"cjr_department\" (\"id\", \"name\") values (2, 'Eng')");
        support.execute("insert into \"cjr_employee\" (\"id\", \"name\", \"age\", \"dept_id\") values (1, 'Ada', 40, 1)");
        support.execute("insert into \"cjr_employee\" (\"id\", \"name\", \"age\", \"dept_id\") values (2, 'Bob', 25, 2)");
        support.execute("insert into \"cjr_employee\" (\"id\", \"name\", \"age\", \"dept_id\") values (3, 'Cara', 35, 1)");
        support.execute("insert into \"cjr_employee\" (\"id\", \"name\", \"age\", \"dept_id\") values (4, 'Dan', 30, null)");
        support.execute("insert into \"cjr_user\" (\"id\", \"name\") values (1, 'alice')");
        support.execute("insert into \"cjr_user\" (\"id\", \"name\") values (2, 'bob')");
        support.execute("insert into \"cjr_profile\" (\"id\", \"bio\", \"user_id\") values (1, 'hello', 1)");
        support.execute("insert into \"cjr_profile\" (\"id\", \"bio\", \"user_id\") values (2, 'world', 2)");

        criteria = new ReactiveCriteriaExecutor(support.operations(), support.dialect(), support.metadataFactory());
    }

    private CriteriaBuilder cb() {
        return criteria.getCriteriaBuilder();
    }

    @Test
    void toManyJoinDeduplicatesAndOrdersParents() {
        // Sales는 age>20 직원 2명(Ada, Cara)이라 join으로 두 번 매치된다. 2단계 실행이 루트 id를 중복 제거해
        // Sales가 한 번만, 정렬(이름 asc: Eng < Sales) 순서로 나와야 한다. 중복 제거가 없으면 Sales가 2번 나온다.
        CriteriaBuilder cb = cb();
        CriteriaQuery<Department> cq = cb.createQuery(Department.class);
        Root<Department> d = cq.from(Department.class);
        Join<Department, Employee> e = d.join("employees");
        cq.select(d).where(cb.gt(e.<Integer>get("age"), 20)).orderBy(cb.asc(d.<String>get("name")));

        StepVerifier.create(criteria.createQuery(cq).getResultList())
                .assertNext(x -> assertEquals("Eng", x.getName()))
                .assertNext(x -> assertEquals("Sales", x.getName()))
                .verifyComplete();
    }

    @Test
    void oneToManyJoinOnConditionMatchesChildren() {
        // scalar 경로(중복 제거 없음): age>33 직원은 Ada(40)/Cara(35) 둘 다 Sales 소속 → Sales가 정확히 2번.
        // parentPK = childFK ON 조건이 두 자식을 모두 매치함을 증명한다.
        CriteriaBuilder cb = cb();
        CriteriaQuery<String> cq = cb.createQuery(String.class);
        Root<Department> d = cq.from(Department.class);
        Join<Department, Employee> e = d.join("employees");
        cq.select(d.<String>get("name")).where(cb.gt(e.<Integer>get("age"), 33))
                .orderBy(cb.asc(d.<String>get("name")));

        StepVerifier.create(criteria.createQuery(cq).getResultList())
                .expectNext("Sales", "Sales")
                .verifyComplete();
    }

    @Test
    void inverseOneToOneJoinRoundTrip() {
        // inverse @OneToOne: User.profile은 컬럼이 없고 owning FK는 Profile.user_id. join ON은 mappedBy로
        // 해석돼 cjr_user.id = cjr_profile.user_id가 되고, bio='hello'인 프로필의 소유자 alice가 나와야 한다.
        CriteriaBuilder cb = cb();
        CriteriaQuery<User> cq = cb.createQuery(User.class);
        Root<User> u = cq.from(User.class);
        Join<User, Profile> p = u.join("profile");
        cq.select(u).where(cb.equal(p.<String>get("bio"), "hello"));

        StepVerifier.create(criteria.createQuery(cq).getResultList())
                .assertNext(x -> assertEquals("alice", x.getName()))
                .verifyComplete();
    }

    // ------------------------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------------------------

    @Entity
    @Table(name = "cjr_department")
    public static class Department {
        @Id
        @Column(name = "id")
        private Long id;
        @Column(name = "name")
        private String name;
        @OneToMany(targetEntity = Employee.class, mappedBy = "department")
        private List<Employee> employees;

        public Long getId() {
            return id;
        }

        public String getName() {
            return name;
        }

        public List<Employee> getEmployees() {
            return employees;
        }
    }

    @Entity
    @Table(name = "cjr_employee")
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
    @Table(name = "cjr_user")
    public static class User {
        @Id
        @Column(name = "id")
        private Long id;
        @Column(name = "name")
        private String name;
        @OneToOne(targetEntity = Profile.class, mappedBy = "user")
        private Profile profile;

        public Long getId() {
            return id;
        }

        public String getName() {
            return name;
        }

        public Profile getProfile() {
            return profile;
        }
    }

    @Entity
    @Table(name = "cjr_profile")
    public static class Profile {
        @Id
        @Column(name = "id")
        private Long id;
        @Column(name = "bio")
        private String bio;
        @OneToOne(targetEntity = User.class)
        @JoinColumn(name = "user_id")
        private User user;

        public Long getId() {
            return id;
        }

        public String getBio() {
            return bio;
        }

        public User getUser() {
            return user;
        }
    }
}
