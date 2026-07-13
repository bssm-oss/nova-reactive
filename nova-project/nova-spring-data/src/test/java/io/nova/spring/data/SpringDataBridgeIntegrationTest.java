package io.nova.spring.data;

import io.nova.core.EntityStateDetector;
import io.nova.core.ReactiveEntityOperations;
import io.nova.core.SimpleReactiveEntityOperations;
import io.nova.dialect.h2.H2Dialect;
import io.nova.metadata.DefaultNamingStrategy;
import io.nova.metadata.EntityMetadataFactory;
import io.nova.query.Criteria;
import io.nova.query.QuerySpec;
import io.nova.r2dbc.R2dbcSqlExecutor;
import io.nova.r2dbc.R2dbcTransactionManager;
import io.nova.sql.Dialect;
import io.nova.sql.SqlStatement;
import io.nova.tx.SimpleReactiveTransactionOperations;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 표준 Spring Data {@code Pageable}/{@code Sort}를 받는 repository 브릿지 오버로드가 실제
 * production {@link H2Dialect} + r2dbc-h2 driver 위에서 올바른 Spring {@code Page}/{@code Slice}를
 * 반환하는지 end-to-end로 검증한다. dialect SQL 렌더링, R2DBC bind marker, LIMIT/OFFSET/ORDER BY가
 * 실제 driver에 거부되지 않는지 회귀 보호한다.
 */
class SpringDataBridgeIntegrationTest {

    private ReactiveEntityOperations operations;
    private AccountRepository repository;

    @BeforeEach
    void setUp() {
        String db = "novabridge_" + UUID.randomUUID().toString().replace("-", "");
        ConnectionFactory connectionFactory =
                ConnectionFactories.get("r2dbc:h2:mem:///" + db + "?DB_CLOSE_DELAY=-1");
        Dialect dialect = new H2Dialect();
        EntityMetadataFactory metadataFactory = new EntityMetadataFactory(new DefaultNamingStrategy());
        R2dbcSqlExecutor sqlExecutor = new R2dbcSqlExecutor(connectionFactory, dialect);
        R2dbcTransactionManager transactionManager = new R2dbcTransactionManager(connectionFactory);
        SimpleReactiveTransactionOperations transactionOperations =
                new SimpleReactiveTransactionOperations(transactionManager);
        SimpleReactiveEntityOperations ops = new SimpleReactiveEntityOperations(
                metadataFactory, dialect, sqlExecutor, new EntityStateDetector(), transactionOperations);
        this.operations = ops;

        String ddl = ops.createTableSql(Account.class);
        StepVerifier.create(sqlExecutor.execute(new SqlStatement(ddl, List.of())))
                .expectNextCount(1)
                .verifyComplete();

        // 10건 insert: name = user01..user10, active = 짝수만 true
        Flux<Account> inserts = Flux.range(1, 10)
                .map(i -> new Account(null, String.format("user%02d", i), i % 2 == 0))
                .concatMap(ops::save);
        StepVerifier.create(inserts.then()).verifyComplete();

        this.repository = newProxy(operations);
    }

    private static AccountRepository newProxy(ReactiveEntityOperations operations) {
        SimpleReactiveRepository handler =
                new SimpleReactiveRepository(Account.class, Long.class, operations);
        return (AccountRepository) Proxy.newProxyInstance(
                AccountRepository.class.getClassLoader(),
                new Class<?>[]{AccountRepository.class},
                handler);
    }

    @Test
    @DisplayName("findAll(Spring Pageable)은 정렬 적용 후 Spring Page(content/total/hasNext)를 반환")
    void findAllBySpringPageableReturnsSpringPage() {
        Pageable pageable = PageRequest.of(1, 3, Sort.by(Sort.Order.asc("name")));

        StepVerifier.create(repository.findAll(pageable))
                .assertNext(page -> {
                    assertEquals(3, page.getContent().size());
                    assertEquals(10L, page.getTotalElements());
                    assertEquals(4, page.getTotalPages(), "ceil(10/3)");
                    assertEquals(1, page.getNumber());
                    assertEquals(3, page.getSize());
                    assertTrue(page.hasNext());
                    assertTrue(page.hasPrevious());
                    // page 1(0-based) with asc name → user04, user05, user06
                    assertEquals("user04", page.getContent().get(0).getName());
                    assertEquals("user06", page.getContent().get(2).getName());
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("findAll(Spring Sort)은 전체를 정렬해 Flux로 반환")
    void findAllBySpringSortReturnsSortedFlux() {
        StepVerifier.create(repository.findAll(Sort.by(Sort.Order.desc("name"))).map(Account::getName))
                .expectNext("user10", "user09", "user08", "user07", "user06",
                        "user05", "user04", "user03", "user02", "user01")
                .verifyComplete();
    }

    @Test
    @DisplayName("findAll(QuerySpec, Spring Pageable)은 명세 필터 + Spring 정렬/페이지를 함께 적용")
    void findAllBySpecAndSpringPageable() {
        QuerySpec spec = QuerySpec.empty().where(Criteria.eq("active", true));
        Pageable pageable = PageRequest.of(0, 2, Sort.by(Sort.Order.asc("name")));

        StepVerifier.create(repository.findAll(spec, pageable))
                .assertNext(page -> {
                    // active=true 는 5건(user02,04,06,08,10)
                    assertEquals(5L, page.getTotalElements());
                    assertEquals(2, page.getContent().size());
                    assertEquals(3, page.getTotalPages(), "ceil(5/2)");
                    assertEquals("user02", page.getContent().get(0).getName());
                    assertEquals("user04", page.getContent().get(1).getName());
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("findSlice(Spring Pageable)은 COUNT 없이 다음 페이지 존재 여부만 담은 Spring Slice를 반환")
    void findSliceBySpringPageable() {
        Pageable pageable = PageRequest.of(0, 4, Sort.by(Sort.Order.asc("name")));

        StepVerifier.create(repository.findSlice(pageable))
                .assertNext(slice -> {
                    assertEquals(4, slice.getContent().size());
                    assertTrue(slice.hasNext(), "총 10건이므로 다음 페이지 존재");
                    assertEquals(0, slice.getNumber());
                    assertFalse(slice.hasPrevious());
                    assertEquals("user01", slice.getContent().get(0).getName());
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("findAll(Pageable.unpaged())은 전체를 단일 Spring Page로 반환")
    void findAllUnpagedReturnsSinglePage() {
        StepVerifier.create(repository.findAll(Pageable.unpaged()))
                .assertNext(page -> {
                    assertEquals(10, page.getContent().size());
                    assertEquals(10L, page.getTotalElements());
                    assertFalse(page.hasNext());
                    assertFalse(page.hasPrevious());
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("findAll(QuerySpec, Pageable)의 Pageable 정렬은 명세의 기존 정렬을 replace")
    void springPageableSortReplacesSpecSort() {
        // 명세에는 name ASC를 걸어 두고, pageable에는 name DESC를 준다 → pageable 정렬이 이겨야 한다.
        QuerySpec spec = QuerySpec.empty().orderBy(io.nova.query.Sort.by(
                io.nova.query.Sort.Order.asc("name")));
        Pageable pageable = PageRequest.of(0, 3, Sort.by(Sort.Order.desc("name")));

        StepVerifier.create(repository.findAll(spec, pageable))
                .assertNext(page -> {
                    assertEquals(10L, page.getTotalElements());
                    // DESC가 적용되면 첫 페이지 = user10, user09, user08
                    assertEquals("user10", page.getContent().get(0).getName());
                    assertEquals("user09", page.getContent().get(1).getName());
                    assertEquals("user08", page.getContent().get(2).getName());
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("findSlice(Pageable.unpaged())은 slice에 페이지 크기 제한이 필수이므로 onError로 fail-fast")
    void findSliceUnpagedFailsFast() {
        // defer로 감싸므로 조립 시점 동기 throw가 아니라 구독 시점 onError 신호로 전파된다.
        StepVerifier.create(repository.findSlice(Pageable.unpaged()))
                .expectErrorMatches(t -> t instanceof IllegalArgumentException
                        && (t.getMessage().contains("unpaged") || t.getMessage().contains("paged")))
                .verify();
    }

    @Entity
    @Table(name = "bridge_accounts")
    public static class Account {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;

        @Column(name = "name")
        private String name;

        @Column(name = "active")
        private boolean active;

        public Account() {
        }

        Account(Long id, String name, boolean active) {
            this.id = id;
            this.name = name;
            this.active = active;
        }

        public Long getId() {
            return id;
        }

        public String getName() {
            return name;
        }

        public boolean isActive() {
            return active;
        }
    }

    interface AccountRepository extends SpringDataReactiveCrudRepository<Account, Long> {
    }
}
