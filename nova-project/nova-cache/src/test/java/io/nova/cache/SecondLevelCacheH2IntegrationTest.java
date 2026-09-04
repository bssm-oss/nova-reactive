package io.nova.cache;

import io.nova.core.EntityStateDetector;
import io.nova.core.ReactiveEntityOperations;
import io.nova.core.SimpleReactiveEntityManager;
import io.nova.core.SimpleReactiveEntityOperations;
import io.nova.core.SqlExecutionListener;
import io.nova.cache.spi.CacheKey;
import io.nova.cache.spi.ReactiveCacheProvider;
import io.nova.dialect.h2.H2Dialect;
import io.nova.metadata.DefaultNamingStrategy;
import io.nova.metadata.EntityMetadataFactory;
import io.nova.r2dbc.R2dbcSqlExecutor;
import io.nova.r2dbc.R2dbcTransactionManager;
import io.nova.schema.SchemaInitializer;
import io.nova.schema.SimpleSchemaInitializer;
import io.nova.sql.SqlStatement;
import io.nova.tx.PhysicalTransactionScope;
import io.nova.tx.ReactiveTransactionOperations;
import io.nova.tx.TransactionContext;
import io.nova.tx.TransactionDefinition;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import jakarta.persistence.Cacheable;
import jakarta.persistence.Access;
import jakarta.persistence.AccessType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.function.Function;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 실제 r2dbc-h2 driver 위에서 2차 캐시의 read-through 히트/write invalidation을 검증한다.
 * SQL 실행을 세는 {@link SqlExecutionListener}를 R2DBC executor에 배선해, 캐시 히트 시 SELECT가
 * <b>발행되지 않는지</b>를 직접 관찰한다 — 캐시 계약을 SQL string 단위 테스트로는 검증할 수 없는 부분이다.
 *
 * <p>배선은 production {@code SimpleReactiveEntityOperations}를 그대로 만들고 {@link NovaCache}로 감싸므로
 * hub 코드 무수정 원칙이 실제 파이프라인에서 지켜지는지도 함께 고정한다.
 */
class SecondLevelCacheH2IntegrationTest {

    private static final AtomicInteger DB_SEQ = new AtomicInteger();

    private ConnectionFactory freshConnectionFactory() {
        int seq = DB_SEQ.incrementAndGet();
        return ConnectionFactories.get("r2dbc:h2:mem:///slcache" + seq + "?options=DB_CLOSE_DELAY=-1");
    }

    private static boolean await(CountDownLatch latch) {
        try {
            return latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while awaiting transaction flush", error);
        }
    }

    private record Wiring(ReactiveEntityOperations cached, SchemaInitializer schema, SelectCountingListener listener,
                          ReactiveCacheProvider cacheProvider, EntityMetadataFactory metadataFactory) {
    }

    private Wiring wire(ConnectionFactory cf) {
        return wire(cf, new R2dbcTransactionManager(cf));
    }

    private Wiring wireLegacy(ConnectionFactory cf) {
        R2dbcTransactionManager physicalManager = new R2dbcTransactionManager(cf);
        ReactiveTransactionOperations legacyManager = new ReactiveTransactionOperations() {
            @Override
            public <T> Mono<T> inTransaction(
                    TransactionDefinition definition, Function<TransactionContext, Mono<T>> callback) {
                return physicalManager.inTransaction(definition, context ->
                        callback.apply(context).contextWrite(
                                reactorContext -> reactorContext.delete(PhysicalTransactionScope.CONTEXT_KEY)));
            }
        };
        return wire(cf, legacyManager);
    }

    private Wiring wire(ConnectionFactory cf, ReactiveTransactionOperations txManager) {
        H2Dialect dialect = new H2Dialect();
        SelectCountingListener listener = new SelectCountingListener();
        EntityMetadataFactory metadataFactory = new EntityMetadataFactory(new DefaultNamingStrategy());
        R2dbcSqlExecutor executor = new R2dbcSqlExecutor(cf, dialect, listener);
        SimpleReactiveEntityOperations base = new SimpleReactiveEntityOperations(
                metadataFactory, dialect, executor, new EntityStateDetector(), txManager);
        ReactiveCacheProvider cacheProvider = new SimpleReactiveCacheProvider();
        ReactiveEntityOperations cached = NovaCache.caching(base, cacheProvider, metadataFactory);
        SchemaInitializer schema = new SimpleSchemaInitializer(base, metadataFactory, dialect);
        return new Wiring(cached, schema, listener, cacheProvider, metadataFactory);
    }

    @Test
    void secondFindByIdIsServedFromCacheWithoutSql() {
        ConnectionFactory cf = freshConnectionFactory();
        Wiring w = wire(cf);

        w.schema().create(Widget.class).block();
        Long id = w.cached().save(new Widget("alpha")).block().id();

        long afterSave = w.listener().selects();
        Widget first = w.cached().findById(Widget.class, id).block();
        long afterFirst = w.listener().selects();
        Widget second = w.cached().findById(Widget.class, id).block();
        long afterSecond = w.listener().selects();

        assertEquals("alpha", first.name());
        assertEquals("alpha", second.name());
        assertTrue(afterFirst > afterSave, "첫 findById는 DB SELECT를 발행해야 한다");
        assertEquals(afterFirst, afterSecond, "두 번째 findById는 캐시 히트로 SELECT를 발행하지 않아야 한다");
    }

    @Test
    void saveInvalidatesCacheAndReReadsFromDatabase() {
        ConnectionFactory cf = freshConnectionFactory();
        Wiring w = wire(cf);

        w.schema().create(Widget.class).block();
        Long id = w.cached().save(new Widget("alpha")).block().id();

        w.cached().findById(Widget.class, id).block();        // 캐시 채움
        w.cached().save(new Widget(id, "beta")).block();       // id 지정 → UPDATE 경로 + evict

        long beforeReload = w.listener().selects();
        Widget reloaded = w.cached().findById(Widget.class, id).block(); // 미스 → DB 재조회

        assertTrue(w.listener().selects() > beforeReload, "save 후 findById는 캐시 미스로 DB를 다시 조회해야 한다");
        assertEquals("beta", reloaded.name(), "무효화 후 조회는 갱신된 값을 반환해야 한다");
    }

    @Test
    void deleteInvalidatesCache() {
        ConnectionFactory cf = freshConnectionFactory();
        Wiring w = wire(cf);

        w.schema().create(Widget.class).block();
        Long id = w.cached().save(new Widget("alpha")).block().id();

        w.cached().findById(Widget.class, id).block();   // 캐시 채움
        w.cached().deleteById(Widget.class, id).block();  // evict

        Widget afterDelete = w.cached().findById(Widget.class, id).block();
        assertNull(afterDelete, "삭제 후에는 stale 캐시가 아니라 DB를 조회해 없음을 확인해야 한다");
    }

    @Test
    void rollbackLeavesNoStaleCache() {
        // 트랜잭션 안 write는 즉시 evict 되고, 롤백되면 DB는 원본으로 되돌아간다. 이후 조회는 캐시 미스로
        // 원본(alpha)을 DB에서 읽어야 하며, 롤백된 미커밋 값(beta)이 캐시에 남아선 안 된다.
        ConnectionFactory cf = freshConnectionFactory();
        Wiring w = wire(cf);

        w.schema().create(Widget.class).block();
        Long id = w.cached().save(new Widget("alpha")).block().id();
        w.cached().findById(Widget.class, id).block(); // 캐시 채움(alpha)

        StepVerifier.create(
                w.cached().inTransaction(tx ->
                        tx.save(new Widget(id, "beta"))
                                .then(Mono.error(new RuntimeException("boom"))))
        ).verifyErrorMessage("boom");

        Widget after = w.cached().findById(Widget.class, id).block();
        assertEquals("alpha", after.name(),
                "롤백 후 조회는 캐시된 미커밋 beta가 아니라 DB의 원본 alpha를 반환해야 한다");
    }

    @Test
    void inTransactionReadIsNotPopulatedToSharedCache() {
        // 트랜잭션 안 findById는 미커밋 읽기 유출을 막기 위해 공유 캐시를 채우지 않아야 한다 →
        // 이후 non-tx findById는 여전히 DB SELECT를 발행한다.
        ConnectionFactory cf = freshConnectionFactory();
        Wiring w = wire(cf);

        w.schema().create(Widget.class).block();
        Long id = w.cached().save(new Widget("alpha")).block().id();

        w.cached().inTransaction(tx -> tx.findById(Widget.class, id)).block(); // in-tx 읽기(캐시 미채움)

        long before = w.listener().selects();
        Widget reloaded = w.cached().findById(Widget.class, id).block();
        assertTrue(w.listener().selects() > before,
                "in-tx 읽기가 캐시를 채웠다면 이 findById가 히트해 SELECT가 없었을 것");
        assertEquals("alpha", reloaded.name());
    }

    @Test
    void physicalReadOnlyTransactionDoesNotClearWarmSharedCache() {
        ConnectionFactory cf = freshConnectionFactory();
        Wiring w = wire(cf);

        w.schema().create(Widget.class).block();
        Long id = w.cached().save(new Widget("alpha")).block().id();
        w.cached().findById(Widget.class, id).block();

        w.cached().inTransaction(tx -> tx.findById(Widget.class, id)).block();
        long beforeHit = w.listener().selects();
        assertEquals("alpha", w.cached().findById(Widget.class, id).block().name());
        assertEquals(beforeHit, w.listener().selects(),
                "a physical read-only transaction must neither clear nor replay-clear a warm shared cache");
    }

    @Test
    void legacySuccessfulWriteClearsWarmSharedCacheAfterCommit() {
        ConnectionFactory cf = freshConnectionFactory();
        Wiring w = wireLegacy(cf);

        w.schema().create(Widget.class).block();
        Long id = w.cached().save(new Widget("alpha")).block().id();
        w.cached().findById(Widget.class, id).block();

        w.cached().inTransaction(tx -> tx.update(new Widget(id, "beta"), List.of("name"))).block();

        assertForcedReload(w, id, "beta");
    }

    @Test
    void legacyErroredWriteDoesNotClearWarmSharedCache() {
        ConnectionFactory cf = freshConnectionFactory();
        Wiring w = wireLegacy(cf);

        w.schema().create(Widget.class).block();
        Long id = w.cached().save(new Widget("alpha")).block().id();
        w.cached().findById(Widget.class, id).block();

        StepVerifier.create(w.cached().inTransaction(tx -> tx.update(new Widget(id, "beta"), List.of("name"))
                .then(Mono.error(new IllegalStateException("rollback")))))
                .verifyErrorMessage("rollback");

        long beforeHit = w.listener().selects();
        assertEquals("alpha", w.cached().findById(Widget.class, id).block().name());
        assertEquals(beforeHit, w.listener().selects(),
                "an errored legacy transaction must not clear the warm shared cache");
    }

    @Test
    void legacyCancelledWriteDoesNotClearWarmSharedCache() {
        ConnectionFactory cf = freshConnectionFactory();
        Wiring w = wireLegacy(cf);

        w.schema().create(Widget.class).block();
        Long id = w.cached().save(new Widget("alpha")).block().id();
        w.cached().findById(Widget.class, id).block();

        CountDownLatch completedWrite = new CountDownLatch(1);
        StepVerifier.create(w.cached().inTransaction(tx -> tx.update(new Widget(id, "beta"), List.of("name"))
                .doOnSuccess(ignored -> completedWrite.countDown())
                .then(Mono.never())))
                .then(() -> assertTrue(await(completedWrite), "legacy write did not complete before cancellation"))
                .thenCancel()
                .verify();

        long beforeHit = w.listener().selects();
        assertEquals("alpha", w.cached().findById(Widget.class, id).block().name());
        assertEquals(beforeHit, w.listener().selects(),
                "a cancelled legacy transaction must not clear the warm shared cache");
    }

    @Test
    void legacyReadOnlyTransactionDoesNotClearWarmSharedCache() {
        ConnectionFactory cf = freshConnectionFactory();
        Wiring w = wireLegacy(cf);

        w.schema().create(Widget.class).block();
        Long id = w.cached().save(new Widget("alpha")).block().id();
        w.cached().findById(Widget.class, id).block();

        w.cached().inTransaction(tx -> tx.findById(Widget.class, id)).block();

        long beforeHit = w.listener().selects();
        assertEquals("alpha", w.cached().findById(Widget.class, id).block().name());
        assertEquals(beforeHit, w.listener().selects(),
                "a read-only legacy transaction must not clear the warm shared cache");
    }

    @Test
    void physicalErrorAndCancellationDoNotReplaySharedCacheClear() {
        ConnectionFactory cf = freshConnectionFactory();
        Wiring w = wire(cf);

        w.schema().create(Widget.class).block();
        Long id = w.cached().save(new Widget("alpha")).block().id();
        w.cached().findById(Widget.class, id).block();

        long beforeRollbackFlush = w.listener().updates();
        StepVerifier.create(w.cached().inTransaction(tx -> tx.findById(Widget.class, id)
                .doOnNext(widget -> widget.name = "beta")
                .then(tx.flush())
                .then(Mono.error(new IllegalStateException("rollback")))))
                .verifyErrorMessage("rollback");
        assertTrue(w.listener().updates() > beforeRollbackFlush,
                "the rollback case must issue actual DML before testing its missing commit replay");
        long beforeErrorHit = w.listener().selects();
        w.cached().findById(Widget.class, id).block();
        assertEquals(beforeErrorHit, w.listener().selects(),
                "an errored physical transaction must not replay-clear the warm cache");

        long beforeCancellationFlush = w.listener().updates();
        CountDownLatch flushed = new CountDownLatch(1);
        StepVerifier.create(w.cached().inTransaction(tx -> tx.findById(Widget.class, id)
                .doOnNext(widget -> widget.name = "beta")
                .then(tx.flush().doOnSuccess(ignored -> flushed.countDown()))
                .then(Mono.never())))
                .then(() -> assertTrue(await(flushed), "flush did not complete before cancellation"))
                .thenCancel()
                .verify();
        assertTrue(w.listener().updates() > beforeCancellationFlush,
                "the cancellation case must issue actual DML before testing its missing commit replay");
        long beforeCancelHit = w.listener().selects();
        w.cached().findById(Widget.class, id).block();
        assertEquals(beforeCancelHit, w.listener().selects(),
                "a cancelled physical transaction must not replay-clear the warm cache");
    }

    @Test
    void managedTransactionBypassesWarmCacheAndEvictsAfterDirtyCommit() {
        ConnectionFactory cf = freshConnectionFactory();
        Wiring w = wire(cf);

        w.schema().create(Widget.class).block();
        Long id = w.cached().save(new Widget("alpha")).block().id();
        w.cached().findById(Widget.class, id).block();

        long beforeTransaction = w.listener().selects();
        w.cached().inTransaction(tx -> tx.findById(Widget.class, id)
                .doOnNext(widget -> widget.name = "beta"))
                .block();

        long beforeReload = w.listener().selects();
        Widget afterCommit = w.cached().findById(Widget.class, id).block();

        assertTrue(beforeReload > beforeTransaction,
                "managed transaction must SELECT instead of returning the warm cache value");
        assertTrue(w.listener().updates() > 0, "managed mutation must flush an UPDATE at commit");
        assertTrue(w.listener().selects() > beforeReload, "post-commit eviction must force a DB reload");
        assertEquals("beta", afterCommit.name());
    }

    @Test
    void entityManagerCapturedDecoratorBypassesWarmCacheAndFlushesDirtyCommit() {
        ConnectionFactory cf = freshConnectionFactory();
        Wiring w = wire(cf);
        SimpleReactiveEntityManager entityManager = new SimpleReactiveEntityManager(w.cached(), w.metadataFactory());

        w.schema().create(Widget.class).block();
        Long id = w.cached().save(new Widget("alpha")).block().id();
        w.cached().findById(Widget.class, id).block();

        long beforeTransaction = w.listener().selects();
        entityManager.inTransaction(em -> em.find(Widget.class, id)
                .doOnNext(widget -> widget.name = "beta"))
                .block();

        long beforeReload = w.listener().selects();
        Widget reloaded = w.cached().findById(Widget.class, id).block();
        assertTrue(beforeReload > beforeTransaction, "captured EntityManager must bypass warm cache in a transaction");
        assertTrue(w.listener().selects() > beforeReload, "commit must replay the shared cache clear");
        assertEquals("beta", reloaded.name());
    }

    @Test
    void originalDecoratorUpdateAndDeleteReplayEvictionAfterCommit() {
        ConnectionFactory cf = freshConnectionFactory();
        Wiring w = wire(cf);

        w.schema().create(Widget.class).block();
        Long id = w.cached().save(new Widget("alpha")).block().id();
        CacheKey key = new CacheKey(Widget.class.getName(), Widget.class, id);
        Widget alpha = w.cached().findById(Widget.class, id).block();

        w.cached().inTransaction(ignored -> w.cached().update(new Widget(id, "beta"), List.of("name"))
                .then(w.cacheProvider().getCache(Widget.class.getName()).put(key, alpha))).block();
        assertForcedReload(w, id, "beta");

        Widget beta = w.cached().findById(Widget.class, id).block();
        w.cached().inTransaction(ignored -> w.cached().update(new Widget(id, "gamma"), List.of("name"))
                .then(w.cacheProvider().getCache(Widget.class.getName()).put(key, beta))).block();
        assertForcedReload(w, id, "gamma");

        Widget gamma = w.cached().findById(Widget.class, id).block();
        w.cached().inTransaction(ignored -> w.cached().delete(gamma)
                .then(w.cacheProvider().getCache(Widget.class.getName()).put(key, gamma))).block();
        long beforeReload = w.listener().selects();
        assertNull(w.cached().findById(Widget.class, id).block());
        assertTrue(w.listener().selects() > beforeReload, "post-commit delete eviction must clear repopulated stale value");
    }

    @Test
    void capturedEntityManagerWriteOnlyReplaysEvictionAfterCommit() {
        ConnectionFactory cf = freshConnectionFactory();
        Wiring w = wire(cf);
        SimpleReactiveEntityManager entityManager = new SimpleReactiveEntityManager(w.cached(), w.metadataFactory());

        w.schema().create(Widget.class).block();
        Long id = w.cached().save(new Widget("alpha")).block().id();
        Widget alpha = w.cached().findById(Widget.class, id).block();
        CacheKey key = new CacheKey(Widget.class.getName(), Widget.class, id);

        entityManager.inTransaction(em -> em.find(Widget.class, id)
                .flatMap(managed -> em.remove(managed)
                        .then(w.cacheProvider().getCache(Widget.class.getName()).put(key, alpha)))).block();

        long beforeReload = w.listener().selects();
        assertNull(w.cached().findById(Widget.class, id).block());
        assertTrue(w.listener().selects() > beforeReload,
                "captured EntityManager write must replay eviction after commit");
    }

    private static void assertForcedReload(Wiring wiring, Long id, String expectedName) {
        long beforeReload = wiring.listener().selects();
        Widget reloaded = wiring.cached().findById(Widget.class, id).block();
        assertTrue(wiring.listener().selects() > beforeReload, "post-commit eviction must clear repopulated stale value");
        assertEquals(expectedName, reloaded.name());
    }

    @Test
    void nestedParticipatingLoadRetainsEvictionUntilPhysicalCommit() {
        ConnectionFactory cf = freshConnectionFactory();
        Wiring w = wire(cf);

        w.schema().create(Widget.class).block();
        Long id = w.cached().save(new Widget("alpha")).block().id();
        Widget stale = w.cached().findById(Widget.class, id).block();
        CacheKey key = new CacheKey(Widget.class.getName(), Widget.class, id);

        w.cached().inTransaction(outer -> outer.inTransaction(inner ->
                        inner.findById(Widget.class, id)
                                .doOnNext(widget -> widget.name = "beta")
                                .then(inner.flush())
                                .then(w.cacheProvider().getCache(Widget.class.getName()).put(key, stale)))
                .then())
                .block();

        long beforeReload = w.listener().selects();
        Widget reloaded = w.cached().findById(Widget.class, id).block();
        assertTrue(w.listener().selects() > beforeReload,
                "nested invalidation must still run after the outer physical commit");
        assertEquals("beta", reloaded.name());
    }

    @Test
    void nonCacheableAssociatedEntityWriteEvictsCacheablePropertyOwner() {
        ConnectionFactory cf = freshConnectionFactory();
        Wiring w = wire(cf);

        w.schema().create(PropertyPart.class, PropertyOwner.class).block();
        PropertyPart part = w.cached().save(new PropertyPart("alpha")).block();
        Long ownerId = w.cached().save(new PropertyOwner("owner", part)).block().getId();

        PropertyOwner first = w.cached().findById(PropertyOwner.class, ownerId).block();
        first.getPart().setName("mutated");
        long beforeHit = w.listener().selects();
        PropertyOwner hit = w.cached().findById(PropertyOwner.class, ownerId).block();
        assertEquals(beforeHit, w.listener().selects(), "second owner lookup must be a cache hit");
        assertNotSame(first, hit);
        assertNotSame(first.getPart(), hit.getPart());
        assertEquals("alpha", hit.getPart().getName(), "a hit must expose a fresh detached association graph");

        w.cached().inTransaction(tx -> tx.findById(PropertyPart.class, part.id())
                .doOnNext(managed -> managed.setName("beta"))).block();
        long beforeReload = w.listener().selects();
        PropertyOwner reloaded = w.cached().findById(PropertyOwner.class, ownerId).block();
        assertTrue(w.listener().selects() > beforeReload,
                "a non-cacheable associated write must clear cached owner graphs");
        assertEquals("beta", reloaded.getPart().getName());
    }

    @Test
    void associatedWriteReplaysGlobalEvictionAfterCommit() {
        ConnectionFactory cf = freshConnectionFactory();
        Wiring w = wire(cf);

        w.schema().create(PropertyPart.class, PropertyOwner.class).block();
        PropertyPart part = w.cached().save(new PropertyPart("alpha")).block();
        Long ownerId = w.cached().save(new PropertyOwner("owner", part)).block().getId();
        PropertyOwner stale = w.cached().findById(PropertyOwner.class, ownerId).block();
        CacheKey ownerKey = new CacheKey(PropertyOwner.class.getName(), PropertyOwner.class, ownerId);

        w.cached().inTransaction(tx -> tx.findById(PropertyPart.class, part.id())
                .doOnNext(managed -> managed.setName("beta"))
                .then(w.cacheProvider().getCache(PropertyOwner.class.getName()).put(ownerKey, stale))).block();

        long beforeReload = w.listener().selects();
        PropertyOwner reloaded = w.cached().findById(PropertyOwner.class, ownerId).block();
        assertTrue(w.listener().selects() > beforeReload,
                "physical commit must replay a global clear after a stale owner is repopulated in the transaction");
        assertEquals("beta", reloaded.getPart().getName());
    }

    // --- SQL 실행 카운터 -----------------------------------------------------

    static final class SelectCountingListener implements SqlExecutionListener {
        private final AtomicLong selectCount = new AtomicLong();
        private final AtomicLong updateCount = new AtomicLong();

        @Override
        public void onBeforeExecution(SqlStatement statement) {
            String sql = statement.sql().stripLeading();
            if (sql.regionMatches(true, 0, "select", 0, "select".length())) {
                selectCount.incrementAndGet();
            }
            if (sql.regionMatches(true, 0, "update", 0, "update".length())) {
                updateCount.incrementAndGet();
            }
        }

        long selects() {
            return selectCount.get();
        }

        long updates() {
            return updateCount.get();
        }
    }

    // --- fixture ------------------------------------------------------------

    @Entity
    @Table(name = "cache_widget")
    @Cacheable
    static class Widget {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;

        @Column(name = "name")
        private String name;

        Widget() {
        }

        Widget(String name) {
            this.name = name;
        }

        Widget(Long id, String name) {
            this.id = id;
            this.name = name;
        }

        Long id() {
            return id;
        }

        String name() {
            return name;
        }
    }

    @Entity
    @Table(name = "cache_property_part")
    static class PropertyPart {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;
        private String name;

        PropertyPart() {
        }

        PropertyPart(String name) {
            this.name = name;
        }

        PropertyPart(Long id, String name) {
            this.id = id;
            this.name = name;
        }

        Long id() {
            return id;
        }

        String getName() {
            return name;
        }

        void setName(String name) {
            this.name = name;
        }
    }

    @Entity
    @Table(name = "cache_property_owner")
    @Cacheable
    @Access(AccessType.PROPERTY)
    static class PropertyOwner {
        private Long id;
        private String name;
        private PropertyPart part;

        PropertyOwner() {
        }

        PropertyOwner(String name, PropertyPart part) {
            this.name = name;
            this.part = part;
        }

        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        Long getId() {
            return id;
        }

        void setId(Long id) {
            this.id = id;
        }

        String getName() {
            return name;
        }

        void setName(String name) {
            this.name = name;
        }

        @ManyToOne
        PropertyPart getPart() {
            return part;
        }

        void setPart(PropertyPart part) {
            this.part = part;
        }
    }
}
