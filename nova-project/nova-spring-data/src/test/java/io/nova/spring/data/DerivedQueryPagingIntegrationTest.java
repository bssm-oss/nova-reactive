package io.nova.spring.data;

import io.nova.core.EntityStateDetector;
import io.nova.core.ReactiveEntityOperations;
import io.nova.core.SimpleReactiveEntityOperations;
import io.nova.dialect.h2.H2Dialect;
import io.nova.metadata.DefaultNamingStrategy;
import io.nova.metadata.EntityMetadataFactory;
import io.nova.query.Page;
import io.nova.query.Pageable;
import io.nova.query.Slice;
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
 * T2b: 파생 쿼리 메서드가 Nova {@link Pageable}을 받아 {@code Flux<T>}(LIMIT/OFFSET), {@code Mono<Page<T>>}
 * (총계 포함), {@code Mono<Slice<T>>}(hasNext)를 실 {@link H2Dialect} + r2dbc-h2 driver 위에서 왕복
 * 반환하는지 검증한다. Page의 totalElements는 LIMIT을 제거한 predicate 전체 count여야 하고, Slice는
 * COUNT 없이 다음 페이지 존재만 판정해야 한다.
 */
class DerivedQueryPagingIntegrationTest {

    private AccountRepository repository;

    @BeforeEach
    void setUp() {
        String db = "novaderivedpage_" + UUID.randomUUID().toString().replace("-", "");
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

        String ddl = ops.createTableSql(Account.class);
        StepVerifier.create(sqlExecutor.execute(new SqlStatement(ddl, List.of())))
                .expectNextCount(1)
                .verifyComplete();

        // 10건: name = user01..user10, active = 짝수만 true (=> active=true 5건)
        Flux<Account> inserts = Flux.range(1, 10)
                .map(i -> new Account(null, String.format("user%02d", i), i % 2 == 0))
                .concatMap(ops::save);
        StepVerifier.create(inserts.then()).verifyComplete();

        this.repository = newProxy(ops);
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
    @DisplayName("Flux + Pageable: LIMIT/OFFSET + OrderBy 적용된 한 페이지만 스트리밍")
    void fluxPageableStreamsOnePage() {
        // active=true 5건(user02,04,06,08,10) 중 name ASC 2건 offset 2 => user06, user08
        StepVerifier.create(
                        repository.findByActiveTrueOrderByName(Pageable.of(2, 2L)).map(Account::getName))
                .expectNext("user06", "user08")
                .verifyComplete();
    }

    @Test
    @DisplayName("Mono<Page>: content + totalElements(LIMIT 제거한 predicate 전체 count)")
    void monoPageIncludesTotalCount() {
        StepVerifier.create(repository.findByActiveOrderByName(true, Pageable.of(2, 0L)))
                .assertNext(page -> {
                    assertEquals(5L, page.totalElements(), "active=true 전체는 5건");
                    assertEquals(2, page.content().size());
                    assertEquals(3, page.totalPages(), "ceil(5/2)");
                    assertEquals(0, page.number());
                    assertTrue(page.hasNext());
                    assertFalse(page.hasPrevious());
                    assertEquals("user02", page.content().get(0).getName());
                    assertEquals("user04", page.content().get(1).getName());
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Mono<Page>: 마지막 페이지는 hasNext=false, content는 남은 만큼만")
    void monoPageLastPage() {
        StepVerifier.create(repository.findByActiveOrderByName(true, Pageable.of(2, 4L)))
                .assertNext(page -> {
                    assertEquals(5L, page.totalElements());
                    assertEquals(1, page.content().size(), "5건 중 마지막 1건");
                    assertEquals(2, page.number());
                    assertFalse(page.hasNext());
                    assertTrue(page.hasPrevious());
                    assertEquals("user10", page.content().get(0).getName());
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Mono<Slice>: COUNT 없이 hasNext 판정(다음 페이지 있음)")
    void monoSliceHasNextTrue() {
        // active=false 5건(user01,03,05,07,09), limit 3 첫 페이지 => hasNext=true
        StepVerifier.create(repository.findByActiveFalseOrderByName(Pageable.of(3, 0L)))
                .assertNext(slice -> {
                    assertEquals(3, slice.content().size());
                    assertTrue(slice.hasNext(), "active=false 5건 > 3");
                    assertEquals(0, slice.number());
                    assertFalse(slice.hasPrevious());
                    assertEquals("user01", slice.content().get(0).getName());
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Mono<Slice>: 마지막 페이지는 hasNext=false")
    void monoSliceHasNextFalse() {
        // active=false 5건, offset 3 => 남은 2건(user07,user09), limit 3 => hasNext=false
        StepVerifier.create(repository.findByActiveFalseOrderByName(Pageable.of(3, 3L)))
                .assertNext(slice -> {
                    assertEquals(2, slice.content().size());
                    assertFalse(slice.hasNext());
                    assertTrue(slice.hasPrevious());
                    assertEquals("user07", slice.content().get(0).getName());
                    assertEquals("user09", slice.content().get(1).getName());
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Mono<Page> + predicate(GreaterThan) + Pageable: 필터 + 페이지 + 총계")
    void monoPageWithComparisonPredicate() {
        // id 는 1..10. id > 3 => 7건. 페이지 크기 3, 첫 페이지.
        StepVerifier.create(repository.findByIdGreaterThanOrderByName(3L, Pageable.of(3, 0L)))
                .assertNext(page -> {
                    assertEquals(7L, page.totalElements());
                    assertEquals(3, page.content().size());
                    assertEquals(3, page.totalPages(), "ceil(7/3)");
                })
                .verifyComplete();
    }

    // ------------------------------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------------------------------

    @Entity
    @Table(name = "accounts_derived_page")
    public static class Account {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        @Column(name = "id")
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

        public String getName() {
            return name;
        }
    }

    interface AccountRepository extends ReactiveCrudRepository<Account, Long> {

        Flux<Account> findByActiveTrueOrderByName(Pageable pageable);

        Mono<Page<Account>> findByActiveOrderByName(boolean active, Pageable pageable);

        // active=false(홀수 user01,03,05,07,09 = 5건)로 Slice 검증 — 이름이 findByActiveTrueOrderByName과
        // 겹치지 않으면서 유효한 파생 이름이 되도록 서로 다른 predicate를 쓴다.
        Mono<Slice<Account>> findByActiveFalseOrderByName(Pageable pageable);

        Mono<Page<Account>> findByIdGreaterThanOrderByName(Long id, Pageable pageable);
    }
}
