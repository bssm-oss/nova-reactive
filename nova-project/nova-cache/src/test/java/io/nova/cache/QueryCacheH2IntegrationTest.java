package io.nova.cache;

import io.nova.core.EntityStateDetector;
import io.nova.core.ReactiveEntityOperations;
import io.nova.core.SimpleReactiveEntityOperations;
import io.nova.core.SqlExecutionListener;
import io.nova.dialect.h2.H2Dialect;
import io.nova.metadata.DefaultNamingStrategy;
import io.nova.metadata.EntityMetadataFactory;
import io.nova.query.Criteria;
import io.nova.query.QuerySpec;
import io.nova.r2dbc.R2dbcSqlExecutor;
import io.nova.r2dbc.R2dbcTransactionManager;
import io.nova.schema.SchemaInitializer;
import io.nova.schema.SimpleSchemaInitializer;
import io.nova.sql.SqlStatement;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import jakarta.persistence.Cacheable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 실제 r2dbc-h2 driver 위에서 <b>쿼리 결과 캐시</b>의 read-through 히트(0 SQL)와 write invalidation을 검증한다.
 * SELECT 실행을 세는 {@link SqlExecutionListener}로 두 번째 동일 쿼리가 DB SELECT를 발행하지 않는지 직접
 * 관찰한다 — SQL string 단위 테스트로는 볼 수 없는 캐시 계약이다.
 */
class QueryCacheH2IntegrationTest {

    private static final AtomicInteger DB_SEQ = new AtomicInteger();

    private ConnectionFactory freshConnectionFactory() {
        int seq = DB_SEQ.incrementAndGet();
        return ConnectionFactories.get("r2dbc:h2:mem:///qcache" + seq + "?options=DB_CLOSE_DELAY=-1");
    }

    private record Wiring(ReactiveEntityOperations cached, SchemaInitializer schema, SelectCountingListener listener) {
    }

    private Wiring wire(ConnectionFactory cf) {
        H2Dialect dialect = new H2Dialect();
        SelectCountingListener listener = new SelectCountingListener();
        EntityMetadataFactory metadataFactory = new EntityMetadataFactory(new DefaultNamingStrategy());
        R2dbcSqlExecutor executor = new R2dbcSqlExecutor(cf, dialect, listener);
        R2dbcTransactionManager txManager = new R2dbcTransactionManager(cf);
        SimpleReactiveEntityOperations base = new SimpleReactiveEntityOperations(
                metadataFactory, dialect, executor, new EntityStateDetector(), txManager);
        ReactiveEntityOperations cached = NovaCache.cachingWithQueryCache(
                base, new SimpleReactiveCacheProvider(), metadataFactory, new SimpleReactiveQueryCache());
        SchemaInitializer schema = new SimpleSchemaInitializer(base, metadataFactory, dialect);
        return new Wiring(cached, schema, listener);
    }

    @Test
    void secondIdenticalQueryIsServedFromCacheWithoutSql() {
        ConnectionFactory cf = freshConnectionFactory();
        Wiring w = wire(cf);

        w.schema().create(Item.class).block();
        w.cached().save(new Item("alpha")).block();
        w.cached().save(new Item("beta")).block();

        QuerySpec all = QuerySpec.empty();
        long beforeFirst = w.listener().selects();
        List<Item> first = w.cached().findAll(Item.class, all).collectList().block();
        long afterFirst = w.listener().selects();
        List<Item> second = w.cached().findAll(Item.class, all).collectList().block();
        long afterSecond = w.listener().selects();

        assertEquals(2, first.size());
        assertEquals(2, second.size());
        assertTrue(afterFirst > beforeFirst, "첫 findAll은 DB SELECT를 발행해야 한다");
        assertEquals(afterFirst, afterSecond, "두 번째 동일 findAll은 쿼리 캐시 히트로 SELECT를 발행하지 않아야 한다");
    }

    @Test
    void writeInvalidatesQueryCacheAndReExecutes() {
        ConnectionFactory cf = freshConnectionFactory();
        Wiring w = wire(cf);

        w.schema().create(Item.class).block();
        w.cached().save(new Item("alpha")).block();

        QuerySpec all = QuerySpec.empty();
        w.cached().findAll(Item.class, all).collectList().block();   // 캐시 채움(1건)
        w.cached().findAll(Item.class, all).collectList().block();   // 히트

        w.cached().save(new Item("beta")).block();                    // write → 쿼리 캐시 무효화

        long beforeReload = w.listener().selects();
        List<Item> reloaded = w.cached().findAll(Item.class, all).collectList().block(); // 미스 → 재조회

        assertTrue(w.listener().selects() > beforeReload, "write 후 findAll은 캐시 미스로 DB를 다시 조회해야 한다");
        assertEquals(2, reloaded.size(), "무효화 후 조회는 갱신된 결과(2건)를 반환해야 한다");
    }

    @Test
    void predicateScopedQueryHitsCacheAndInvalidatesOnWrite() {
        ConnectionFactory cf = freshConnectionFactory();
        Wiring w = wire(cf);

        w.schema().create(Item.class).block();
        w.cached().save(new Item("alpha")).block();
        w.cached().save(new Item("beta")).block();

        QuerySpec byName = QuerySpec.empty().where(Criteria.eq("name", "alpha"));
        long beforeFirst = w.listener().selects();
        assertEquals(1, w.cached().findAll(Item.class, byName).collectList().block().size());
        long afterFirst = w.listener().selects();
        assertEquals(1, w.cached().findAll(Item.class, byName).collectList().block().size());
        assertEquals(afterFirst, w.listener().selects(), "동일 predicate 쿼리 재실행은 캐시 히트여야 한다");
        assertTrue(afterFirst > beforeFirst);

        w.cached().save(new Item("gamma")).block(); // 같은 타입 write → 보수적으로 이 predicate 결과도 무효화
        long beforeReload = w.listener().selects();
        w.cached().findAll(Item.class, byName).collectList().block();
        assertTrue(w.listener().selects() > beforeReload, "같은 타입 write는 predicate-scoped 쿼리도 재조회를 유발해야 한다");
    }

    @Test
    void rollbackLeavesNoStaleQueryCache() {
        // 트랜잭션 안 write는 쿼리 캐시를 즉시 무효화한다. 롤백되면 DB는 원본으로 되돌아가고, 이후 조회는
        // 캐시 미스로 원본을 다시 읽어야 하며 롤백된 미커밋 행이 캐시 결과에 남아선 안 된다.
        ConnectionFactory cf = freshConnectionFactory();
        Wiring w = wire(cf);

        w.schema().create(Item.class).block();
        w.cached().save(new Item("alpha")).block();

        QuerySpec all = QuerySpec.empty();
        w.cached().findAll(Item.class, all).collectList().block(); // 캐시 채움(1건)

        StepVerifier.create(
                w.cached().inTransaction(tx ->
                        tx.save(new Item("beta"))
                                .then(Mono.error(new RuntimeException("boom"))))
        ).verifyErrorMessage("boom");

        List<Item> after = w.cached().findAll(Item.class, all).collectList().block();
        assertEquals(1, after.size(), "롤백 후 조회는 캐시된 미커밋 결과가 아니라 DB 원본(1건)을 반환해야 한다");
        assertEquals("alpha", after.get(0).name());
    }

    // --- SQL 실행 카운터 -----------------------------------------------------

    static final class SelectCountingListener implements SqlExecutionListener {
        private final AtomicLong selectCount = new AtomicLong();

        @Override
        public void onBeforeExecution(SqlStatement statement) {
            String sql = statement.sql().stripLeading();
            if (sql.regionMatches(true, 0, "select", 0, "select".length())) {
                selectCount.incrementAndGet();
            }
        }

        long selects() {
            return selectCount.get();
        }
    }

    // --- fixture ------------------------------------------------------------

    @Entity
    @Table(name = "cache_item")
    @Cacheable
    static class Item {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;

        @Column(name = "name")
        private String name;

        Item() {
        }

        Item(String name) {
            this.name = name;
        }

        Long id() {
            return id;
        }

        String name() {
            return name;
        }
    }
}
