package io.nova.spring.data;

import io.nova.core.EntityStateDetector;
import io.nova.core.ReactiveEntityOperations;
import io.nova.core.SimpleReactiveEntityOperations;
import io.nova.dialect.h2.H2Dialect;
import io.nova.metadata.DefaultNamingStrategy;
import io.nova.metadata.EntityMetadataFactory;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 이번 서브스코프에서 확장한 derived query 키워드({@code findTop<N>By}/{@code findFirst<N>By}의 명시적
 * 개수, {@code IgnoreCase})가 실 {@link H2Dialect} + r2dbc-h2 driver 위에서 SQL 왕복까지 정상 동작하는지
 * 검증한다. in-memory driver 없이 SQL string만 단위 검증하면 dialect 렌더링/바인딩 문제를 놓칠 수 있어
 * production 경로(AnnotatedQueryIntegrationTest와 동일한 배선)를 그대로 사용한다.
 */
class DerivedQueryExtendedKeywordsIntegrationTest {

    private AccountRepository repository;

    @BeforeEach
    void setUp() {
        String db = "novaderivedext_" + UUID.randomUUID().toString().replace("-", "");
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

        Flux<Account> inserts = Flux.just(
                        new Account(null, "Ada", "ada@nova.io", 50, true),
                        new Account(null, "Bob", "bob@nova.io", 40, true),
                        new Account(null, "Cara", "cara@nova.io", 30, false),
                        new Account(null, "Dan", "DAN@NOVA.IO", 20, true),
                        new Account(null, "Eve", "eve@nova.io", 10, true))
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
    @DisplayName("findTop3ByActiveTrueOrderByScoreDesc: LIMIT 3, 내림차순")
    void findTopNAppliesLimitAndOrder() {
        StepVerifier.create(repository.findTop3ByActiveTrueOrderByScoreDesc().map(Account::getName))
                .expectNext("Ada", "Bob", "Dan")
                .verifyComplete();
    }

    @Test
    @DisplayName("findFirst2ByActiveFalseOrderByScoreAsc: LIMIT 2, 오름차순 — 매치가 1건뿐이면 1건만")
    void findFirstNWithoutFilter() {
        StepVerifier.create(repository.findFirst2ByActiveFalseOrderByScoreAsc().map(Account::getName))
                .expectNext("Cara")
                .verifyComplete();
    }

    @Test
    @DisplayName("findTop1By 는 기존 findFirstBy 와 동일하게 Mono 단건")
    void findTop1ByBehavesLikeFindFirstBy() {
        StepVerifier.create(repository.findTop1ByActiveTrueOrderByScoreAsc())
                .assertNext(a -> assertEquals("Eve", a.getName()))
                .verifyComplete();
    }

    @Test
    @DisplayName("findByEmailIgnoreCase: 대소문자 무시 등가 비교가 실 DB에서 매치된다")
    void ignoreCaseEqualityMatchesAcrossCase() {
        StepVerifier.create(repository.findByEmailIgnoreCase("dan@nova.io"))
                .assertNext(a -> assertEquals("Dan", a.getName()))
                .verifyComplete();
    }

    @Test
    @DisplayName("findByEmailContainingIgnoreCase: substring 매치도 대소문자 무시")
    void ignoreCaseContainingMatchesAcrossCase() {
        StepVerifier.create(repository.findByEmailContainingIgnoreCase("NOVA.IO").map(Account::getName))
                .expectNextCount(5)
                .verifyComplete();

        StepVerifier.create(repository.findByEmailContainingIgnoreCase("dan").map(Account::getName))
                .expectNext("Dan")
                .verifyComplete();
    }

    @Test
    @DisplayName("Like(대소문자 구분)는 대소문자가 다르면 매치하지 않는다 — IgnoreCase 대조군")
    void plainLikeStaysCaseSensitive() {
        StepVerifier.create(repository.findByEmailContaining("dan").map(Account::getName))
                .verifyComplete();
    }

    @Test
    @DisplayName("findTop0By는 프록시 호출 시점에 IllegalArgumentException으로 fail-fast")
    void findTopZeroFailsAtCallTime() {
        assertThrows(IllegalArgumentException.class, () -> repository.findTop0ByActiveTrue());
    }

    @Test
    @DisplayName("GreaterThanIgnoreCase 조합은 fail-fast — non-string keyword에 IgnoreCase 무의미")
    void ignoreCaseOnGreaterThanFailsAtCallTime() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> repository.findByScoreGreaterThanIgnoreCase(10));
        assertTrue(ex.getMessage().contains("IgnoreCase"));
    }

    // ------------------------------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------------------------------

    @Entity
    @Table(name = "accounts_derived_ext")
    public static class Account {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        @Column(name = "id")
        private Long id;
        @Column(name = "name")
        private String name;
        @Column(name = "email")
        private String email;
        @Column(name = "score")
        private int score;
        @Column(name = "active")
        private boolean active;

        public Account() {
        }

        Account(Long id, String name, String email, int score, boolean active) {
            this.id = id;
            this.name = name;
            this.email = email;
            this.score = score;
            this.active = active;
        }

        public String getName() {
            return name;
        }
    }

    interface AccountRepository extends ReactiveCrudRepository<Account, Long> {

        Flux<Account> findTop3ByActiveTrueOrderByScoreDesc();

        Flux<Account> findFirst2ByActiveFalseOrderByScoreAsc();

        Mono<Account> findTop1ByActiveTrueOrderByScoreAsc();

        Mono<Account> findByEmailIgnoreCase(String email);

        Flux<Account> findByEmailContainingIgnoreCase(String chunk);

        Flux<Account> findByEmailContaining(String chunk);

        Flux<Account> findTop0ByActiveTrue();

        Flux<Account> findByScoreGreaterThanIgnoreCase(int score);
    }
}
