package io.nova.wave2;

import io.nova.cache.NovaCache;
import io.nova.cache.SimpleReactiveCacheProvider;
import io.nova.core.EntityStateDetector;
import io.nova.core.ReactiveEntityManager;
import io.nova.core.ReactiveEntityOperations;
import io.nova.core.SimpleReactiveEntityManager;
import io.nova.core.SimpleReactiveEntityOperations;
import io.nova.core.SqlExecutionListener;
import io.nova.dialect.h2.H2Dialect;
import io.nova.graph.EntityGraph;
import io.nova.graph.EntityGraphs;
import io.nova.metadata.DefaultNamingStrategy;
import io.nova.metadata.EntityMetadataFactory;
import io.nova.query.jpql.JpqlExecutor;
import io.nova.query.jpql.NamedQueryRegistry;
import io.nova.query.criteria.ReactiveCriteriaExecutor;
import io.nova.r2dbc.R2dbcSqlExecutor;
import io.nova.r2dbc.R2dbcTransactionManager;
import io.nova.schema.SchemaInitializer;
import io.nova.schema.SimpleSchemaInitializer;
import io.nova.sql.Dialect;
import io.nova.sql.SqlStatement;
import io.nova.spring.data.EnableNovaRepositories;
import io.nova.spring.data.Modifying;
import io.nova.spring.data.Param;
import io.nova.spring.data.Query;
import io.nova.spring.data.ReactiveCrudRepository;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import jakarta.persistence.Cacheable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.NamedAttributeNode;
import jakarta.persistence.NamedEntityGraph;
import jakarta.persistence.NamedNativeQuery;
import jakarta.persistence.NamedQuery;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Root;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wave2 교차 기능(cross-feature) E2E. 개별 모듈 H2 통합 테스트가 이미 green인 상태에서, Wave2 신규 기능이
 * 서로 그리고 Wave1과 함께 <b>한 애플리케이션 흐름/한 DB</b> 위에서 동작하는지 실제 r2dbc-h2 driver로 검증한다.
 *
 * <p>대상 기능: C1c JPA Criteria API({@link ReactiveCriteriaExecutor}), C1d
 * {@code @NamedQuery}/{@code @NamedNativeQuery}({@link NamedQueryRegistry}), C6a {@code @Query} 애너테이션
 * (실 Spring 컨테이너 {@link EnableNovaRepositories} 부팅 경로), C3 EntityGraph/JPQL JOIN FETCH
 * ({@link EntityGraphs}/{@link JpqlExecutor}). Wave1의 2차 캐시/EntityManager/JPQL와의 조합도 포함한다.
 *
 * <p>모든 협력자(base operations, cache 데코레이터, EntityManager, JpqlExecutor, CriteriaExecutor,
 * NamedQueryRegistry, 그리고 Spring 컨테이너의 repository)는 <em>동일한</em> {@link EntityMetadataFactory}와
 * 동일한 H2 DB를 공유하도록 배선해 실제 상호작용을 재현한다. {@code @Query} 경로는 수동 배선이 아니라 실제
 * {@link AnnotationConfigApplicationContext} + {@link EnableNovaRepositories} 스캔으로 부팅한다.
 */
class Wave2CrossFeatureE2ETest {

    private static final AtomicInteger DB_SEQ = new AtomicInteger();

    private AnnotationConfigApplicationContext context;

    @AfterEach
    void tearDown() {
        if (context != null) {
            context.close();
            context = null;
        }
    }

    // ------------------------------------------------------------------------------------------
    // Wiring — 한 DB 위에 Wave2 기능 + Wave1 캐시/EM/JPQL 을 모두 얹은 애플리케이션 배선
    // ------------------------------------------------------------------------------------------

    /**
     * {@code @Configuration} @Bean 이 공유하는 미리 배선된 협력자 홀더. 테스트는 순차 실행되며 각 테스트는
     * {@link #wire()}에서 이 홀더를 새로 채운 뒤 컨테이너를 refresh 한다 — 컨테이너의 {@code novaEntityOperations}
     * 는 나머지 배선과 <em>동일한</em> base operations 인스턴스가 되어 완전한 단일 배선을 이룬다.
     */
    static final class Shared {
        static Dialect dialect;
        static EntityMetadataFactory metadataFactory;
        static SimpleReactiveEntityOperations base;
    }

    private record Wiring(
            SimpleReactiveEntityOperations base,
            ReactiveEntityOperations cached,
            ReactiveEntityManager entityManager,
            JpqlExecutor jpqlOnBase,
            ReactiveCriteriaExecutor criteria,
            NamedQueryRegistry namedQueries,
            EntityGraphs graphs,
            EmployeeRepository employeeRepository,
            StatementListener listener) {
    }

    private Wiring wire() {
        int seq = DB_SEQ.incrementAndGet();
        String db = "wave2e2e" + seq;
        ConnectionFactory cf = ConnectionFactories.get(
                "r2dbc:h2:mem:///" + db + "?options=DB_CLOSE_DELAY=-1");
        Dialect dialect = new H2Dialect();
        StatementListener listener = new StatementListener();
        EntityMetadataFactory metadataFactory = new EntityMetadataFactory(new DefaultNamingStrategy());
        R2dbcSqlExecutor executor = new R2dbcSqlExecutor(cf, dialect, listener);
        R2dbcTransactionManager txManager = new R2dbcTransactionManager(cf);

        SimpleReactiveEntityOperations base = new SimpleReactiveEntityOperations(
                metadataFactory, dialect, executor, new EntityStateDetector(), txManager);
        // 2차 캐시 데코레이터는 base를 감싼다. EntityManager는 캐시 데코레이터 위에 얹어 write invalidation과
        // flush 위임(nova-cache flush override)을 모두 실제 경로로 태운다.
        ReactiveEntityOperations cached =
                NovaCache.caching(base, new SimpleReactiveCacheProvider(), metadataFactory);
        ReactiveEntityManager em = new SimpleReactiveEntityManager(cached, metadataFactory);
        JpqlExecutor jpql = new JpqlExecutor(base, dialect, metadataFactory,
                Employee.class, Team.class, Member.class);
        ReactiveCriteriaExecutor criteria = new ReactiveCriteriaExecutor(base, dialect, metadataFactory);
        NamedQueryRegistry namedQueries = new NamedQueryRegistry(base, dialect, metadataFactory, Employee.class);
        EntityGraphs graphs = new EntityGraphs(metadataFactory);

        SchemaInitializer schema = new SimpleSchemaInitializer(base, metadataFactory, dialect);
        schema.create(Employee.class, Team.class, Member.class).block();

        // 실 Spring 컨테이너로 repository 부팅 — @EnableNovaRepositories 스캔 경로(수동 배선 아님).
        Shared.dialect = dialect;
        Shared.metadataFactory = metadataFactory;
        Shared.base = base;
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
        ctx.register(RepositoryConfig.class);
        ctx.refresh();
        this.context = ctx;
        EmployeeRepository repo = ctx.getBean(EmployeeRepository.class);

        return new Wiring(base, cached, em, jpql, criteria, namedQueries, graphs, repo, listener);
    }

    /** name = emp01.., salary = i * 100, department = 짝수 "sales"/홀수 "eng". */
    private void seedEmployees(Wiring w, int count) {
        Flux<Employee> inserts = Flux.range(1, count)
                .map(i -> new Employee(String.format("emp%02d", i),
                        new BigDecimal(i * 100).setScale(2), i % 2 == 0 ? "sales" : "eng"))
                .concatMap(w.base()::save);
        StepVerifier.create(inserts.then()).verifyComplete();
    }

    // ==========================================================================================
    // C1c — JPA Criteria API 라운드트립(술어/정렬/집계/groupBy)
    // ==========================================================================================

    @Test
    void criteriaPredicateOrderAndAggregateRoundTrip() {
        Wiring w = wire();
        seedEmployees(w, 6); // salary 100..600, sales=짝수(200,400,600), eng=홀수(100,300,500)

        // 술어 + 정렬: salary >= 300 → emp03..emp06(4건), 이름 오름차순.
        CriteriaBuilder cb = w.criteria().getCriteriaBuilder();
        CriteriaQuery<Employee> cq = cb.createQuery(Employee.class);
        Root<Employee> e = cq.from(Employee.class);
        cq.select(e)
                .where(cb.ge(e.<BigDecimal>get("salary"), new BigDecimal("300")))
                .orderBy(cb.asc(e.<String>get("name")));
        StepVerifier.create(w.criteria().createQuery(cq).getResultList().map(Employee::getName))
                .expectNext("emp03", "emp04", "emp05", "emp06")
                .verifyComplete();

        // 집계 COUNT(sales) = 3.
        CriteriaBuilder cb2 = w.criteria().getCriteriaBuilder();
        CriteriaQuery<Object> countQ = cb2.createQuery(Object.class);
        Root<Employee> e2 = countQ.from(Employee.class);
        countQ.select(cb2.count(e2)).where(cb2.equal(e2.<String>get("department"), "sales"));
        StepVerifier.create(w.criteria().createQuery(countQ).getSingleResult()
                        .map(v -> ((Number) v).longValue()))
                .expectNext(3L)
                .verifyComplete();
    }

    @Test
    void criteriaAndJpqlAgreeOnSameData() {
        // 같은 데이터에 Criteria 집계와 Wave1 JPQL 집계가 일관해야 한다.
        Wiring w = wire();
        seedEmployees(w, 6); // SUM(salary) = 100+200+...+600 = 2100

        CriteriaBuilder cb = w.criteria().getCriteriaBuilder();
        CriteriaQuery<Object> sumQ = cb.createQuery(Object.class);
        Root<Employee> e = sumQ.from(Employee.class);
        sumQ.select(cb.sum(e.<BigDecimal>get("salary")));
        long criteriaSum = ((Number) w.criteria().createQuery(sumQ).getSingleResult().block()).longValue();

        long jpqlSum = ((Number) w.jpqlOnBase()
                .createQuery("SELECT SUM(e.salary) FROM Employee e")
                .getSingleResult().block()).longValue();

        assertEquals(2100L, criteriaSum, "Criteria SUM");
        assertEquals(criteriaSum, jpqlSum, "Criteria와 JPQL 집계가 같은 데이터에서 일치해야 한다");
    }

    // ==========================================================================================
    // C1d — @NamedQuery / @NamedNativeQuery 라운드트립
    // ==========================================================================================

    @Test
    void namedJpqlAndNativeQueriesExecute() {
        Wiring w = wire();
        seedEmployees(w, 4); // salary 100..400

        // 명명 JPQL: salary >= 250 → emp03, emp04.
        StepVerifier.create(
                        w.namedQueries().createQuery("Employee.byMinSalary", Employee.class)
                                .setParameter("min", new BigDecimal("250"))
                                .getResultList()
                                .map(Employee::getName))
                .expectNext("emp03", "emp04")
                .verifyComplete();

        // 명명 네이티브 COUNT.
        StepVerifier.create(
                        w.namedQueries().createNativeQuery("Employee.countAll",
                                        row -> row.get("cnt", Long.class))
                                .getSingleResult())
                .expectNext(4L)
                .verifyComplete();
    }

    @Test
    void namedQueryAgreesWithCriteriaOnSameData() {
        Wiring w = wire();
        seedEmployees(w, 5); // salary 100..500, >=300 → emp03,emp04,emp05

        List<String> named = w.namedQueries().createQuery("Employee.byMinSalary", Employee.class)
                .setParameter("min", new BigDecimal("300"))
                .getResultList().map(Employee::getName).collectList().block();

        CriteriaBuilder cb = w.criteria().getCriteriaBuilder();
        CriteriaQuery<Employee> cq = cb.createQuery(Employee.class);
        Root<Employee> e = cq.from(Employee.class);
        cq.select(e).where(cb.ge(e.<BigDecimal>get("salary"), new BigDecimal("300")))
                .orderBy(cb.asc(e.<String>get("name")));
        List<String> criteria = w.criteria().createQuery(cq).getResultList()
                .map(Employee::getName).collectList().block();

        assertEquals(List.of("emp03", "emp04", "emp05"), named, "@NamedQuery 결과");
        assertEquals(named, criteria, "@NamedQuery와 Criteria가 같은 데이터에서 일치해야 한다");
    }

    // ==========================================================================================
    // C6a — @Query 애너테이션(실 Spring 컨테이너 부팅 경로)
    // ==========================================================================================

    @Test
    void atQueryJpqlNativeAndModifyingThroughContainer() {
        Wiring w = wire();
        seedEmployees(w, 5); // salary 100..500

        // JPQL @Query + named 파라미터.
        StepVerifier.create(w.employeeRepository().withMinSalary(new BigDecimal("300")).map(Employee::getName))
                .expectNext("emp03", "emp04", "emp05")
                .verifyComplete();

        // native @Query 단건.
        StepVerifier.create(w.employeeRepository().nativeByName("emp02"))
                .assertNext(emp -> assertEquals(0, emp.getSalary().compareTo(new BigDecimal("200.00"))))
                .verifyComplete();

        // @Modifying JPQL 벌크 UPDATE: eng 부서(홀수: emp01,emp03,emp05) salary +1000.
        StepVerifier.create(w.employeeRepository().raiseDepartment("eng", new BigDecimal("1000")))
                .expectNext(3L)
                .verifyComplete();
        StepVerifier.create(w.employeeRepository().nativeByName("emp01").map(Employee::getSalary)
                        .map(s -> s.compareTo(new BigDecimal("1100.00"))))
                .expectNext(0)
                .verifyComplete();
    }

    @Test
    void atQueryStandardPageableReturnsSpringPage() {
        Wiring w = wire();
        seedEmployees(w, 10);

        // 표준 Spring Data Pageable 브릿지로 페이징 — page 1(0-based), size 3 → emp04,emp05,emp06.
        // 정렬은 @Query 문자열의 ORDER BY e.name 로 표현한다(브릿지는 Pageable.getSort() 를 fail-fast 로 거부).
        Pageable pageable = PageRequest.of(1, 3);
        StepVerifier.create(w.employeeRepository().pageByName(pageable))
                .assertNext(page -> {
                    assertEquals(10L, page.getTotalElements());
                    assertEquals(4, page.getTotalPages(), "ceil(10/3)");
                    assertEquals(3, page.getContent().size());
                    assertEquals("emp04", page.getContent().get(0).getName());
                    assertEquals("emp06", page.getContent().get(2).getName());
                    assertTrue(page.hasNext());
                    assertTrue(page.hasPrevious());
                })
                .verifyComplete();
    }

    // ==========================================================================================
    // C3 — EntityGraph / JPQL JOIN FETCH (배치 로드, N+1 없음)
    // ==========================================================================================

    @Test
    void namedEntityGraphBatchLoadsMembersWithoutNPlusOne() {
        Wiring w = wire();
        seedTeams(w);

        EntityGraph<Team> graph = w.graphs().named(Team.class, "Team.withMembers");
        List<Team> teams = new ArrayList<>();

        w.listener().clear();
        StepVerifier.create(w.base().findAll(Team.class, graph))
                .recordWith(() -> teams)
                .expectNextCount(2)
                .verifyComplete();

        teams.sort((a, b) -> a.getName().compareTo(b.getName()));
        assertEquals(2, teams.get(0).getMembers().size(), "alpha 팀 멤버 2명");
        assertEquals(1, teams.get(1).getMembers().size(), "beta 팀 멤버 1명");
        // 팀이 2개여도 SELECT 는 고정: root 1 + members IN 1 = 2 (N+1 없음).
        assertEquals(2, w.listener().selects(), "부모 수와 무관하게 SELECT 고정 — N+1 없음: " + w.listener().statements());
    }

    @Test
    void jpqlJoinFetchBatchLoadsMembers() {
        Wiring w = wire();
        seedTeams(w);

        List<Team> teams = new ArrayList<>();
        StepVerifier.create(
                        w.jpqlOnBase().createQuery("SELECT t FROM Team t JOIN FETCH t.members ORDER BY t.name",
                                        Team.class)
                                .getResultList())
                .recordWith(() -> teams)
                .expectNextCount(2)
                .verifyComplete();

        assertEquals("alpha", teams.get(0).getName());
        assertEquals(2, teams.get(0).getMembers().size());
        assertEquals(1, teams.get(1).getMembers().size());
    }

    // ==========================================================================================
    // 기능 조합(interaction) — Wave2 ↔ Wave1
    // ==========================================================================================

    @Test
    void entityManagerPersistIsVisibleToJpqlCriteriaAndNamedQuery() {
        // EntityManager(캐시 데코레이터 위)로 저장한 데이터를 JPQL/Criteria/@NamedQuery/@Query 가 모두 일관되게
        // 조회해야 한다(한 DB, 여러 read 표면).
        Wiring w = wire();

        StepVerifier.create(
                        w.entityManager().persist(new Employee("zoe", new BigDecimal("900.00"), "eng"))
                                .then(w.entityManager().persist(new Employee("amy", new BigDecimal("100.00"), "sales")))
                                .then())
                .verifyComplete();

        // JPQL COUNT = 2.
        assertEquals(2L, ((Number) w.jpqlOnBase().createQuery("SELECT COUNT(e) FROM Employee e")
                .getSingleResult().block()).longValue());

        // Criteria: salary >= 500 → zoe.
        CriteriaBuilder cb = w.criteria().getCriteriaBuilder();
        CriteriaQuery<Employee> cq = cb.createQuery(Employee.class);
        Root<Employee> e = cq.from(Employee.class);
        cq.select(e).where(cb.ge(e.<BigDecimal>get("salary"), new BigDecimal("500")));
        StepVerifier.create(w.criteria().createQuery(cq).getResultList().map(Employee::getName))
                .expectNext("zoe").verifyComplete();

        // @NamedQuery: salary >= 100, 이름 오름차순 → amy, zoe.
        StepVerifier.create(w.namedQueries().createQuery("Employee.byMinSalary", Employee.class)
                        .setParameter("min", new BigDecimal("100"))
                        .getResultList().map(Employee::getName))
                .expectNext("amy", "zoe").verifyComplete();

        // @Query(컨테이너): salary >= 100 → amy, zoe.
        StepVerifier.create(w.employeeRepository().withMinSalary(new BigDecimal("100")).map(Employee::getName))
                .expectNext("amy", "zoe").verifyComplete();
    }

    @Test
    void criteriaResultReadThroughCacheHitsSecondTime() {
        // Criteria 로 얻은 엔티티 id 를 캐시 데코레이터로 조회하면 2번째는 캐시 히트(SELECT 없음)여야 한다.
        Wiring w = wire();
        seedEmployees(w, 3);

        CriteriaBuilder cb = w.criteria().getCriteriaBuilder();
        CriteriaQuery<Employee> cq = cb.createQuery(Employee.class);
        Root<Employee> e = cq.from(Employee.class);
        cq.select(e).where(cb.equal(e.<String>get("name"), "emp02"));
        Employee viaCriteria = w.criteria().createQuery(cq).getSingleResult().block();
        assertNotNull(viaCriteria);
        Long id = viaCriteria.getId();

        long beforeFirst = w.listener().selects();
        Employee first = w.cached().findById(Employee.class, id).block();
        long afterFirst = w.listener().selects();
        Employee second = w.cached().findById(Employee.class, id).block();
        long afterSecond = w.listener().selects();

        assertEquals("emp02", first.getName());
        assertEquals("emp02", second.getName());
        assertTrue(afterFirst > beforeFirst, "첫 캐시 findById 는 DB SELECT 발행");
        assertEquals(afterFirst, afterSecond, "두 번째 findById 는 캐시 히트로 SELECT 없음");
    }

    // ==========================================================================================
    // 최종보완 회귀 — [Wave1 E2E MINOR] 캐시 데코레이터 위 EntityManager.flush() 가 delegate.flush() 로 위임
    // ==========================================================================================

    @Test
    void entityManagerFlushOverCacheDecoratorPushesSessionUpdateMidTransaction() {
        // nova-cache flush override 회귀: EntityManager 가 캐시 데코레이터 위에 얹혀 있어도 em.flush() 가
        // 세션 dirty 를 즉시 DB로 밀어내야 한다(default no-op 이면 flush 시점에 UPDATE 가 없고 commit auto-flush
        // 로만 반영돼 이 mid-transaction 관찰이 0이 된다).
        Wiring w = wire();
        Long id = w.base().save(new Employee("kay", new BigDecimal("100.00"), "eng")).map(Employee::getId).block();

        AtomicLong updatesAtFlush = new AtomicLong(-1);
        w.listener().clear();
        StepVerifier.create(
                        w.entityManager().inTransaction(e ->
                                e.find(Employee.class, id)
                                        .doOnNext(emp -> emp.setName("kay-renamed"))
                                        .then(e.flush())
                                        .then(Mono.fromRunnable(() ->
                                                updatesAtFlush.set(w.listener().count("w2_employee", "update"))))))
                .verifyComplete();

        assertTrue(updatesAtFlush.get() >= 1,
                "em.flush() 가 캐시 데코레이터를 통해 세션 UPDATE 를 즉시 밀어내야 한다(관찰된 update="
                        + updatesAtFlush.get() + "): " + w.listener().statements());

        // commit 후 갱신 값 확인.
        StepVerifier.create(w.base().findById(Employee.class, id).map(Employee::getName))
                .expectNext("kay-renamed").verifyComplete();
    }

    // ------------------------------------------------------------------------------------------
    // Seed helpers
    // ------------------------------------------------------------------------------------------

    private void seedTeams(Wiring w) {
        Team alpha = w.base().save(new Team("alpha")).block();
        Team beta = w.base().save(new Team("beta")).block();
        w.base().save(new Member("ann", alpha)).block();
        w.base().save(new Member("bob", alpha)).block();
        w.base().save(new Member("cid", beta)).block();
    }

    // ------------------------------------------------------------------------------------------
    // SQL 실행 관찰 리스너
    // ------------------------------------------------------------------------------------------

    private static final class StatementListener implements SqlExecutionListener {
        private final List<String> statements = new CopyOnWriteArrayList<>();
        private final AtomicLong selectCount = new AtomicLong();

        @Override
        public void onBeforeExecution(SqlStatement statement) {
            statements.add(statement.sql());
            if (statement.sql().stripLeading().regionMatches(true, 0, "select", 0, "select".length())) {
                selectCount.incrementAndGet();
            }
        }

        void clear() {
            statements.clear();
        }

        long selects() {
            return selectCount.get();
        }

        List<String> statements() {
            return statements;
        }

        long count(String table, String op) {
            return statements.stream()
                    .map(sql -> sql.toLowerCase(Locale.ROOT))
                    .filter(sql -> sql.contains(table) && sql.startsWith(op))
                    .count();
        }
    }

    // ------------------------------------------------------------------------------------------
    // Spring 컨테이너 구성 — @EnableNovaRepositories 스캔 경로. @Bean 은 Shared 홀더의 배선을 그대로 노출해
    // 컨테이너와 나머지 배선이 동일한 base operations/dialect/metadataFactory 를 공유한다.
    // ------------------------------------------------------------------------------------------

    @Configuration(proxyBeanMethods = false)
    @EnableNovaRepositories(basePackageClasses = EmployeeRepository.class)
    static class RepositoryConfig {

        @Bean
        ReactiveEntityOperations novaEntityOperations() {
            return Shared.base;
        }

        @Bean
        Dialect dialect() {
            return Shared.dialect;
        }

        @Bean
        EntityMetadataFactory entityMetadataFactory() {
            return Shared.metadataFactory;
        }
    }

    // ------------------------------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------------------------------

    @Entity
    @Table(name = "w2_employee")
    @Cacheable
    @NamedQuery(name = "Employee.byMinSalary",
            query = "SELECT e FROM Employee e WHERE e.salary >= :min ORDER BY e.name")
    @NamedNativeQuery(name = "Employee.countAll",
            query = "SELECT COUNT(*) AS \"cnt\" FROM \"w2_employee\"")
    public static class Employee {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;
        @Column(name = "name")
        private String name;
        @Column(name = "salary")
        private BigDecimal salary;
        @Column(name = "department")
        private String department;

        public Employee() {
        }

        public Employee(String name, BigDecimal salary, String department) {
            this.name = name;
            this.salary = salary;
            this.department = department;
        }

        public Long getId() {
            return id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public BigDecimal getSalary() {
            return salary;
        }

        public String getDepartment() {
            return department;
        }
    }

    @Entity
    @Table(name = "w2_team")
    @NamedEntityGraph(name = "Team.withMembers", attributeNodes = @NamedAttributeNode("members"))
    public static class Team {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;
        @Column(name = "name")
        private String name;
        @OneToMany(targetEntity = Member.class, mappedBy = "team")
        private List<Member> members;

        public Team() {
        }

        public Team(String name) {
            this.name = name;
        }

        public Long getId() {
            return id;
        }

        public String getName() {
            return name;
        }

        public List<Member> getMembers() {
            return members;
        }
    }

    @Entity
    @Table(name = "w2_member")
    public static class Member {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;
        @Column(name = "name")
        private String name;
        @ManyToOne(targetEntity = Team.class)
        @JoinColumn(name = "team_id")
        private Team team;

        public Member() {
        }

        public Member(String name, Team team) {
            this.name = name;
            this.team = team;
        }

        public String getName() {
            return name;
        }

        public Team getTeam() {
            return team;
        }
    }

    public interface EmployeeRepository extends ReactiveCrudRepository<Employee, Long> {

        @Query("SELECT e FROM Employee e WHERE e.salary >= :min ORDER BY e.name")
        Flux<Employee> withMinSalary(@Param("min") BigDecimal min);

        @Query(value = "SELECT * FROM \"w2_employee\" WHERE \"name\" = :name", nativeQuery = true)
        Mono<Employee> nativeByName(@Param("name") String name);

        @Modifying
        @Query("UPDATE Employee e SET e.salary = e.salary + :delta WHERE e.department = :dept")
        Mono<Long> raiseDepartment(@Param("dept") String dept, @Param("delta") BigDecimal delta);

        @Query("SELECT e FROM Employee e ORDER BY e.name")
        Mono<org.springframework.data.domain.Page<Employee>> pageByName(
                org.springframework.data.domain.Pageable pageable);
    }
}
