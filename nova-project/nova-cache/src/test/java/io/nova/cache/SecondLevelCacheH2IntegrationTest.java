package io.nova.cache;

import io.nova.core.EntityStateDetector;
import io.nova.core.ReactiveEntityOperations;
import io.nova.core.SimpleReactiveEntityOperations;
import io.nova.core.SqlExecutionListener;
import io.nova.dialect.h2.H2Dialect;
import io.nova.metadata.DefaultNamingStrategy;
import io.nova.metadata.EntityMetadataFactory;
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

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        ReactiveEntityOperations cached = NovaCache.caching(base, new SimpleReactiveCacheProvider(), metadataFactory);
        SchemaInitializer schema = new SimpleSchemaInitializer(base, metadataFactory, dialect);
        return new Wiring(cached, schema, listener);
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
}
