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
import io.nova.query.jpql.JpqlExecutor;
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
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code @Query} 애너테이션(JPQL·native·@Modifying·Pageable)이 production {@link H2Dialect} + r2dbc-h2
 * driver 위에서 end-to-end로 동작하는지 검증한다. {@link SimpleReactiveRepository}를 실 {@link JpqlExecutor}
 * + {@link Dialect}와 함께 직접 구성한다(= {@link NovaRepositoryFactoryBean}가 하는 배선과 동일).
 */
class AnnotatedQueryIntegrationTest {

    private ReactiveEntityOperations operations;
    private JpqlExecutor jpqlExecutor;
    private Dialect dialect;
    private AccountRepository repository;

    @BeforeEach
    void setUp() {
        String db = "novaquery_" + UUID.randomUUID().toString().replace("-", "");
        ConnectionFactory connectionFactory =
                ConnectionFactories.get("r2dbc:h2:mem:///" + db + "?DB_CLOSE_DELAY=-1");
        dialect = new H2Dialect();
        EntityMetadataFactory metadataFactory = new EntityMetadataFactory(new DefaultNamingStrategy());
        R2dbcSqlExecutor sqlExecutor = new R2dbcSqlExecutor(connectionFactory, dialect);
        R2dbcTransactionManager transactionManager = new R2dbcTransactionManager(connectionFactory);
        SimpleReactiveTransactionOperations transactionOperations =
                new SimpleReactiveTransactionOperations(transactionManager);
        SimpleReactiveEntityOperations ops = new SimpleReactiveEntityOperations(
                metadataFactory, dialect, sqlExecutor, new EntityStateDetector(), transactionOperations);
        this.operations = ops;
        this.jpqlExecutor = new JpqlExecutor(ops, dialect, metadataFactory, Account.class);

        String ddl = ops.createTableSql(Account.class);
        StepVerifier.create(sqlExecutor.execute(new SqlStatement(ddl, List.of())))
                .expectNextCount(1)
                .verifyComplete();

        Flux<Account> inserts = Flux.just(
                        new Account(null, "Ada", 30),
                        new Account(null, "Bob", 10),
                        new Account(null, "Cara", 20),
                        new Account(null, "Dan", 40),
                        new Account(null, "Eve", 50))
                .concatMap(ops::save);
        StepVerifier.create(inserts.then()).verifyComplete();

        this.repository = newProxy(operations, jpqlExecutor, dialect);
    }

    private static AccountRepository newProxy(ReactiveEntityOperations operations,
                                              JpqlExecutor jpqlExecutor, Dialect dialect) {
        SimpleReactiveRepository handler =
                new SimpleReactiveRepository(Account.class, Long.class, operations, jpqlExecutor, dialect);
        return (AccountRepository) Proxy.newProxyInstance(
                AccountRepository.class.getClassLoader(),
                new Class<?>[]{AccountRepository.class},
                handler);
    }

    @Test
    @DisplayName("JPQL @Query 엔티티 Flux + named 파라미터")
    void jpqlEntityFluxNamed() {
        StepVerifier.create(repository.withMinScore(20).map(Account::getName))
                .expectNext("Ada", "Cara", "Dan", "Eve")
                .verifyComplete();
    }

    @Test
    @DisplayName("JPQL @Query 엔티티 Mono 단건 + positional 파라미터")
    void jpqlEntityMonoPositional() {
        StepVerifier.create(repository.byName("Bob"))
                .assertNext(a -> {
                    assertEquals("Bob", a.getName());
                    assertEquals(10, a.getScore());
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("JPQL @Query 스칼라(String) Flux")
    void jpqlScalarFlux() {
        StepVerifier.create(repository.namesWithMinScore(40))
                .expectNext("Dan", "Eve")
                .verifyComplete();
    }

    @Test
    @DisplayName("native @Query 엔티티 Mono")
    void nativeEntityMono() {
        StepVerifier.create(repository.nativeByName("Cara"))
                .assertNext(a -> {
                    assertEquals("Cara", a.getName());
                    assertEquals(20, a.getScore());
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("@Modifying JPQL 벌크 UPDATE는 영향 행 수를 반환하고 반영된다")
    void modifyingJpqlBulkUpdate() {
        StepVerifier.create(repository.bumpScores(5, 30))
                .expectNext(3L)
                .verifyComplete();

        // score>=30 이었던 Ada(30→35), Dan(40→45), Eve(50→55)만 인상.
        StepVerifier.create(repository.namesWithMinScore(41))
                .expectNext("Dan", "Eve")
                .verifyComplete();
        StepVerifier.create(repository.byName("Ada").map(Account::getScore))
                .expectNext(35)
                .verifyComplete();
    }

    @Test
    @DisplayName("@Modifying native 벌크 DELETE는 영향 행 수를 반환하고 반영된다")
    void modifyingNativeBulkDelete() {
        StepVerifier.create(repository.deleteBelow(20))
                .expectNext(1L)
                .verifyComplete();
        StepVerifier.create(repository.byName("Bob"))
                .verifyComplete();
    }

    @Test
    @DisplayName("Pageable @Query → Nova Page(content + total via count fallback)")
    void pageableNovaPage() {
        StepVerifier.create(repository.page(Pageable.of(2, 0)))
                .assertNext(page -> {
                    assertEquals(5L, page.totalElements());
                    assertEquals(2, page.content().size());
                    assertEquals("Ada", page.content().get(0).getName());
                    assertEquals("Bob", page.content().get(1).getName());
                    assertEquals(3, page.totalPages());
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Pageable @Query + 명시 countQuery → Nova Page")
    void pageableNovaPageWithCountQuery() {
        StepVerifier.create(repository.pageWithCount(Pageable.of(2, 2)))
                .assertNext(page -> {
                    assertEquals(5L, page.totalElements());
                    assertEquals("Cara", page.content().get(0).getName());
                    assertEquals("Dan", page.content().get(1).getName());
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Pageable @Query → Nova Slice(count 없이 hasNext)")
    void pageableNovaSlice() {
        StepVerifier.create(repository.slice(Pageable.of(2, 0)))
                .assertNext(slice -> {
                    assertEquals(2, slice.content().size());
                    assertTrue(slice.hasNext());
                    assertEquals("Ada", slice.content().get(0).getName());
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Spring Pageable @Query → Spring Page")
    void pageableSpringPage() {
        StepVerifier.create(repository.springPage(PageRequest.of(0, 2)))
                .assertNext(page -> {
                    assertEquals(5L, page.getTotalElements());
                    assertEquals(2, page.getContent().size());
                    assertEquals(3, page.getTotalPages());
                    assertEquals("Ada", page.getContent().get(0).getName());
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("JpqlExecutor 미구성 시 JPQL @Query는 명확한 예외로 fail-fast")
    void jpqlWithoutExecutorFailsFast() {
        SimpleReactiveRepository handler =
                new SimpleReactiveRepository(Account.class, Long.class, operations);
        AccountRepository repo = (AccountRepository) Proxy.newProxyInstance(
                AccountRepository.class.getClassLoader(),
                new Class<?>[]{AccountRepository.class}, handler);
        StepVerifier.create(repo.withMinScore(20))
                .expectErrorMatches(t -> t.getMessage() != null && t.getMessage().contains("JpqlExecutor"))
                .verify();
    }

    @Test
    @DisplayName("Dialect 미구성 시 native @Query는 명확한 예외로 fail-fast")
    void nativeWithoutDialectFailsFast() {
        SimpleReactiveRepository handler =
                new SimpleReactiveRepository(Account.class, Long.class, operations);
        AccountRepository repo = (AccountRepository) Proxy.newProxyInstance(
                AccountRepository.class.getClassLoader(),
                new Class<?>[]{AccountRepository.class}, handler);
        StepVerifier.create(repo.nativeByName("Cara"))
                .expectErrorMatches(t -> t.getMessage() != null && t.getMessage().contains("Dialect"))
                .verify();
    }

    // ------------------------------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------------------------------

    @Entity
    @Table(name = "accounts_q")
    public static class Account {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        @Column(name = "id")
        private Long id;
        @Column(name = "name")
        private String name;
        @Column(name = "score")
        private int score;

        public Account() {
        }

        Account(Long id, String name, int score) {
            this.id = id;
            this.name = name;
            this.score = score;
        }

        public Long getId() {
            return id;
        }

        public String getName() {
            return name;
        }

        public int getScore() {
            return score;
        }
    }

    interface AccountRepository extends ReactiveCrudRepository<Account, Long> {

        @Query("SELECT a FROM Account a WHERE a.score >= :min ORDER BY a.name")
        Flux<Account> withMinScore(@Param("min") int min);

        @Query("SELECT a FROM Account a WHERE a.name = ?1")
        Mono<Account> byName(String name);

        @Query("SELECT a.name FROM Account a WHERE a.score >= :min ORDER BY a.name")
        Flux<String> namesWithMinScore(@Param("min") int min);

        @Query(value = "SELECT * FROM \"accounts_q\" WHERE \"name\" = :name", nativeQuery = true)
        Mono<Account> nativeByName(@Param("name") String name);

        @Modifying
        @Query("UPDATE Account a SET a.score = a.score + :delta WHERE a.score >= :min")
        Mono<Long> bumpScores(@Param("delta") int delta, @Param("min") int min);

        @Modifying
        @Query(value = "DELETE FROM \"accounts_q\" WHERE \"score\" < ?1", nativeQuery = true)
        Mono<Long> deleteBelow(int score);

        @Query("SELECT a FROM Account a ORDER BY a.name")
        Mono<Page<Account>> page(Pageable pageable);

        @Query(value = "SELECT a FROM Account a ORDER BY a.name",
                countQuery = "SELECT COUNT(a) FROM Account a")
        Mono<Page<Account>> pageWithCount(Pageable pageable);

        @Query("SELECT a FROM Account a ORDER BY a.name")
        Mono<Slice<Account>> slice(Pageable pageable);

        @Query("SELECT a FROM Account a ORDER BY a.name")
        Mono<org.springframework.data.domain.Page<Account>> springPage(
                org.springframework.data.domain.Pageable pageable);
    }
}
