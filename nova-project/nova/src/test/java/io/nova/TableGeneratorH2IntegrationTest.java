package io.nova;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.TableGenerator;
import io.nova.core.ReactiveEntityOperations;
import io.nova.schema.SchemaInitializer;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * {@code @GeneratedValue(strategy = TABLE)} + {@code @TableGenerator}가 실제 r2dbc-h2 driver 위에서
 * full round-trip 되는지 검증한다 — generator 테이블 자동 생성/seed, save 시 id 주입, 연속 save가 단조
 * 증가하는 id를 받는지까지.
 *
 * <p>SQL string 단위 테스트만으로는 generator 테이블 UPDATE-then-SELECT가 driver에서 받아들여지는지,
 * INSERT가 app-supplied id를 포함하는지(driver-key 회수 경로를 타지 않는지)를 검증할 수 없다. 이 통합
 * 테스트가 production {@link Nova} 배선으로 그 수용성을 고정한다.
 */
class TableGeneratorH2IntegrationTest {

    private static final AtomicInteger DB_SEQ = new AtomicInteger();

    private ConnectionFactory freshConnectionFactory() {
        int seq = DB_SEQ.incrementAndGet();
        return ConnectionFactories.get(
                "r2dbc:h2:mem:///tablegen" + seq + "?options=DB_CLOSE_DELAY=-1");
    }

    @Test
    void defaultTableGeneratorAssignsIncreasingIdsAcrossSaves() {
        ConnectionFactory cf = freshConnectionFactory();
        SchemaInitializer schema = Nova.schemaInitializer(cf);
        ReactiveEntityOperations operations = Nova.create(cf);

        // schema.create가 nova_sequences generator 테이블을 만들고 seed한 뒤 entity 테이블을 만든다.
        StepVerifier.create(
                schema.create(DefaultTableGenAccount.class)
                        .then(operations.save(new DefaultTableGenAccount("alice@example.com")))
                        .flatMap(first -> {
                            assertNotNull(first.getId(), "save 후 TABLE 전략 id가 채워져 있어야 한다");
                            assertEquals(0L, first.getId(),
                                    "@TableGenerator 미지정 → initialValue 기본값 0이 첫 발급 id가 되어야 한다");
                            return operations.save(new DefaultTableGenAccount("bob@example.com"));
                        })
        ).assertNext(second -> {
            assertEquals(1L, second.getId(), "연속 save는 단조 증가하는 id를 받아야 한다");
        }).verifyComplete();
    }

    @Test
    void explicitTableGeneratorHonorsInitialValueAndAllocationBlock() {
        ConnectionFactory cf = freshConnectionFactory();
        SchemaInitializer schema = Nova.schemaInitializer(cf);
        ReactiveEntityOperations operations = Nova.create(cf);

        // initialValue=100, allocationSize=5 → 첫 발급 100, 이후 101, 102, ... (블록 내 in-memory 발급).
        StepVerifier.create(
                schema.create(ExplicitTableGenAccount.class)
                        .thenMany(Flux.range(0, 6)
                                .concatMap(i -> operations.save(new ExplicitTableGenAccount("user" + i + "@example.com")))
                                .map(ExplicitTableGenAccount::getId))
        ).assertNext(id -> assertEquals(100L, id))
                .assertNext(id -> assertEquals(101L, id))
                .assertNext(id -> assertEquals(102L, id))
                .assertNext(id -> assertEquals(103L, id))
                .assertNext(id -> assertEquals(104L, id))
                // 6번째 save는 블록(5개)을 소진하고 새 블록을 확보 → 105.
                .assertNext(id -> assertEquals(105L, id))
                .verifyComplete();
    }

    @Test
    void tableGeneratedIdRoundTripsThroughFindById() {
        ConnectionFactory cf = freshConnectionFactory();
        SchemaInitializer schema = Nova.schemaInitializer(cf);
        ReactiveEntityOperations operations = Nova.create(cf);

        StepVerifier.create(
                schema.create(DefaultTableGenAccount.class)
                        .then(operations.save(new DefaultTableGenAccount("carol@example.com")))
                        .flatMap(saved -> operations.findById(DefaultTableGenAccount.class, saved.getId()))
        ).assertNext(loaded -> {
            assertNotNull(loaded.getId());
            assertEquals("carol@example.com", loaded.getEmail());
        }).verifyComplete();
    }

    @Test
    void concurrentSavesNeverHandOutDuplicateIds() {
        ConnectionFactory cf = freshConnectionFactory();
        SchemaInitializer schema = Nova.schemaInitializer(cf);
        ReactiveEntityOperations operations = Nova.create(cf);

        int total = 60;
        // allocationSize=1 → 매 save가 generator 테이블 select+compare-and-set을 수행한다. 높은 동시성으로
        // 발급해도 read-then-CAS 재시도가 서로소 블록을 보장하므로 id가 절대 중복되지 않아야 한다(비원자
        // UPDATE-후-SELECT였다면 read-after-write 경합으로 중복이 발생했을 경로).
        java.util.List<Long> ids = schema.create(SingleAllocAccount.class)
                .thenMany(Flux.range(0, total)
                        .flatMap(i -> operations.save(new SingleAllocAccount("u" + i + "@example.com")), total)
                        .map(SingleAllocAccount::getId))
                .collectList()
                .block();

        assertNotNull(ids);
        assertEquals(total, ids.size(), "모든 save가 완료되어야 한다");
        assertEquals(total, new java.util.HashSet<>(ids).size(),
                "동시 발급된 TABLE 전략 id는 전부 유일해야 한다(중복 없음)");
    }

    @Entity
    @Table(name = "single_alloc_accounts")
    static class SingleAllocAccount {
        @Id
        @GeneratedValue(strategy = GenerationType.TABLE, generator = "single_gen")
        @TableGenerator(
                name = "single_gen",
                table = "single_id_generators",
                pkColumnName = "gen_name",
                valueColumnName = "gen_value",
                pkColumnValue = "single_account",
                initialValue = 1,
                allocationSize = 1)
        private Long id;

        @Column(name = "email")
        private String email;

        SingleAllocAccount() {
        }

        SingleAllocAccount(String email) {
            this.email = email;
        }

        Long getId() {
            return id;
        }
    }

    @Entity
    @Table(name = "default_table_gen_accounts")
    static class DefaultTableGenAccount {
        @Id
        @GeneratedValue(strategy = GenerationType.TABLE)
        private Long id;

        @Column(name = "email")
        private String email;

        DefaultTableGenAccount() {
        }

        DefaultTableGenAccount(String email) {
            this.email = email;
        }

        Long getId() {
            return id;
        }

        String getEmail() {
            return email;
        }
    }

    @Entity
    @Table(name = "explicit_table_gen_accounts")
    static class ExplicitTableGenAccount {
        @Id
        @GeneratedValue(strategy = GenerationType.TABLE, generator = "acct_gen")
        @TableGenerator(
                name = "acct_gen",
                table = "id_generators",
                pkColumnName = "gen_name",
                valueColumnName = "gen_value",
                pkColumnValue = "explicit_account",
                initialValue = 100,
                allocationSize = 5)
        private Long id;

        @Column(name = "email")
        private String email;

        ExplicitTableGenAccount() {
        }

        ExplicitTableGenAccount(String email) {
            this.email = email;
        }

        Long getId() {
            return id;
        }

        String getEmail() {
            return email;
        }
    }
}
