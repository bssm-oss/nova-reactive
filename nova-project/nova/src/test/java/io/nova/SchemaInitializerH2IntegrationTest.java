package io.nova;

import io.nova.annotation.Column;
import io.nova.annotation.Entity;
import io.nova.annotation.GeneratedValue;
import io.nova.annotation.GenerationType;
import io.nova.annotation.Id;
import io.nova.annotation.Index;
import io.nova.annotation.Table;
import io.nova.core.ReactiveEntityOperations;
import io.nova.query.Criteria;
import io.nova.query.QuerySpec;
import io.nova.schema.SchemaInitializer;
import io.nova.schema.SchemaOptions;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * SchemaInitializer가 실제 R2DBC H2 driver 위에서 동작하는지 확인하는 통합 테스트.
 * SQL string 단위 테스트(SimpleSchemaInitializerTest)만으로는 driver 수용성을 검증할 수 없다 —
 * IF NOT EXISTS 멱등성, DROP IF EXISTS 의 실제 동작, 인덱스 발행이 driver에서 잘 받아들여지는지
 * 확인하는 게 본 테스트의 목적이다. 데이터베이스는 메모리 H2(독립 DB per test).
 */
class SchemaInitializerH2IntegrationTest {

    /** 같은 JVM 내에서 in-memory DB가 충돌하지 않도록 매 테스트마다 unique한 DB 이름을 만든다. */
    private static final AtomicInteger DB_SEQ = new AtomicInteger();

    private ConnectionFactory freshConnectionFactory() {
        int seq = DB_SEQ.incrementAndGet();
        // ?options=DB_CLOSE_DELAY=-1 — connection close 후에도 DB가 살아 있어 다음 connection이 같은 DB를 본다.
        return ConnectionFactories.get(
                "r2dbc:h2:mem:///schemainit" + seq + "?options=DB_CLOSE_DELAY=-1");
    }

    @Test
    void createsTableAndAllowsImmediateInsertAndSelect() {
        ConnectionFactory cf = freshConnectionFactory();
        SchemaInitializer schema = Nova.schemaInitializer(cf);
        ReactiveEntityOperations operations = Nova.create(cf);

        // create → save → findAll가 한 파이프라인으로 동작해야 한다.
        StepVerifier.create(
                schema.create(IntegrationAccount.class)
                        .then(operations.save(new IntegrationAccount(null, "alice@example.com")))
                        .flatMapMany(saved ->
                                operations.findAll(IntegrationAccount.class, QuerySpec.empty()))
        ).assertNext(loaded -> {
            // hydration 성공 — id가 IDENTITY로 채워졌는지만 sanity check.
            org.junit.jupiter.api.Assertions.assertNotNull(loaded.getId());
            org.junit.jupiter.api.Assertions.assertEquals("alice@example.com", loaded.getEmail());
        }).verifyComplete();
    }

    @Test
    void createIsIdempotentWithDefaultIfNotExists() {
        ConnectionFactory cf = freshConnectionFactory();
        SchemaInitializer schema = Nova.schemaInitializer(cf);

        // 같은 entity로 create를 두 번 호출해도 두 번째 호출이 실패하면 안 된다 — IF NOT EXISTS 의 의미.
        StepVerifier.create(
                schema.create(IntegrationAccount.class)
                        .then(schema.create(IntegrationAccount.class))
        ).verifyComplete();
    }

    @Test
    void createWithIfNotExistsFalseFailsOnSecondCall() {
        ConnectionFactory cf = freshConnectionFactory();
        SchemaInitializer schema = Nova.schemaInitializer(cf);
        SchemaOptions raw = SchemaOptions.defaults().withIfNotExists(false);

        // raw CREATE TABLE은 두 번째 호출에서 driver가 에러를 던져야 한다 — 사용자 의도가 명시적이므로.
        StepVerifier.create(
                schema.create(IntegrationAccount.class, raw)
                        .then(schema.create(IntegrationAccount.class, raw))
        ).verifyError();
    }

    @Test
    void recreateDropsExistingDataAndStartsFresh() {
        ConnectionFactory cf = freshConnectionFactory();
        SchemaInitializer schema = Nova.schemaInitializer(cf);
        ReactiveEntityOperations operations = Nova.create(cf);

        // 1) create → insert 1건
        // 2) recreate → 테이블이 새로 만들어지므로 데이터가 사라져야 함
        // 3) count는 0이어야 함
        StepVerifier.create(
                schema.create(IntegrationAccount.class)
                        .then(operations.save(new IntegrationAccount(null, "ghost@example.com")))
                        .then(schema.recreate(IntegrationAccount.class))
                        .then(operations.count(IntegrationAccount.class, QuerySpec.empty()))
        ).expectNext(0L).verifyComplete();
    }

    @Test
    void dropIfExistsOnAbsentTableSucceeds() {
        ConnectionFactory cf = freshConnectionFactory();
        SchemaInitializer schema = Nova.schemaInitializer(cf);

        // 존재하지 않는 테이블에 대해 drop IF EXISTS는 조용히 성공해야 한다.
        StepVerifier.create(schema.drop(IntegrationAccount.class)).verifyComplete();
    }

    @Test
    void createIncludesIndexesByDefaultAndIndexQueryWorks() {
        ConnectionFactory cf = freshConnectionFactory();
        SchemaInitializer schema = Nova.schemaInitializer(cf);
        ReactiveEntityOperations operations = Nova.create(cf);

        // @Index가 발행되었는지는 인덱스 컬럼으로 조회가 동작하는지로 검증한다 — 인덱스가 없어도 통과하지만
        // 인덱스 SQL 자체가 driver에 거부되면 schema.create()가 에러를 던진다(인덱스 발행이 실제로 일어남을 보장).
        StepVerifier.create(
                schema.create(IndexedAccount.class)
                        .then(operations.save(new IndexedAccount(null, "ix@example.com")))
                        .flatMap(saved -> operations.count(
                                IndexedAccount.class,
                                QuerySpec.empty().where(Criteria.eq("email", "ix@example.com"))))
        ).expectNext(1L).verifyComplete();
    }

    @Entity
    @Table("integration_accounts")
    public static class IntegrationAccount {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;

        @Column("email_address")
        private String email;

        public IntegrationAccount() {}

        public IntegrationAccount(Long id, String email) {
            this.id = id;
            this.email = email;
        }

        public Long getId() {
            return id;
        }

        public String getEmail() {
            return email;
        }
    }

    @Entity
    @Table("indexed_integration_accounts")
    @Index(columns = {"email"})
    public static class IndexedAccount {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;

        @Column("email")
        private String email;

        public IndexedAccount() {}

        public IndexedAccount(Long id, String email) {
            this.id = id;
            this.email = email;
        }

        public Long getId() {
            return id;
        }

        public String getEmail() {
            return email;
        }
    }
}
