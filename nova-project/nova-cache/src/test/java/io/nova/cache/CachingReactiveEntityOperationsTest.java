package io.nova.cache;

import io.nova.core.ReactiveEntityOperations;
import io.nova.metadata.DefaultNamingStrategy;
import io.nova.metadata.EntityMetadataFactory;
import io.nova.query.QuerySpec;
import jakarta.persistence.Cacheable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link CachingReactiveEntityOperations} 데코레이터의 read-through 히트와 write invalidation을,
 * DB 조회 횟수를 세는 in-memory fake delegate로 검증한다. delegate가 {@code SimpleReactiveEntityOperations}
 * 내부를 대신하므로 hub 파일을 건드리지 않고 데코레이터 계약만 고립 검증한다.
 */
class CachingReactiveEntityOperationsTest {

    private final EntityMetadataFactory metadataFactory =
            new EntityMetadataFactory(new DefaultNamingStrategy());

    private ReactiveEntityOperations caching(CountingOps delegate) {
        return new CachingReactiveEntityOperations(
                delegate, metadataFactory, new CacheConfigurationResolver(), new SimpleReactiveCacheProvider());
    }

    @Test
    void secondFindByIdHitsCacheNotDelegate() {
        CountingOps delegate = new CountingOps(metadataFactory);
        delegate.seed(new Product(1L, "keyboard"));
        ReactiveEntityOperations ops = caching(delegate);

        assertEquals("keyboard", ops.findById(Product.class, 1L).block().name);
        assertEquals("keyboard", ops.findById(Product.class, 1L).block().name);

        assertEquals(1, delegate.findByIdCalls.get(), "두 번째 findById는 캐시 히트로 delegate를 호출하지 않아야 한다");
    }

    @Test
    void saveEvictsCachedEntry() {
        CountingOps delegate = new CountingOps(metadataFactory);
        delegate.seed(new Product(1L, "keyboard"));
        ReactiveEntityOperations ops = caching(delegate);

        ops.findById(Product.class, 1L).block();           // 미스 → delegate 호출 #1, 캐시 채움
        ops.save(new Product(1L, "mouse")).block();          // write → evict
        ops.findById(Product.class, 1L).block();             // 캐시 비워졌으므로 delegate 호출 #2

        assertEquals(2, delegate.findByIdCalls.get());
    }

    @Test
    void deleteEvictsCachedEntry() {
        CountingOps delegate = new CountingOps(metadataFactory);
        Product p = new Product(1L, "keyboard");
        delegate.seed(p);
        ReactiveEntityOperations ops = caching(delegate);

        ops.findById(Product.class, 1L).block();  // delegate #1 + 캐시
        ops.findById(Product.class, 1L).block();  // 히트
        ops.delete(p).block();                    // evict
        ops.findById(Product.class, 1L).block();  // delegate #2

        assertEquals(2, delegate.findByIdCalls.get());
    }

    @Test
    void deleteByIdEvictsCachedEntry() {
        CountingOps delegate = new CountingOps(metadataFactory);
        delegate.seed(new Product(1L, "keyboard"));
        ReactiveEntityOperations ops = caching(delegate);

        ops.findById(Product.class, 1L).block();       // delegate #1 + 캐시
        ops.deleteById(Product.class, 1L).block();     // evict
        ops.findById(Product.class, 1L).block();       // delegate #2

        assertEquals(2, delegate.findByIdCalls.get());
    }

    @Test
    void nonCacheableEntityAlwaysHitsDelegate() {
        CountingOps delegate = new CountingOps(metadataFactory);
        delegate.seed(new Ledger(1L, "acme"));
        ReactiveEntityOperations ops = caching(delegate);

        ops.findById(Ledger.class, 1L).block();
        ops.findById(Ledger.class, 1L).block();

        assertEquals(2, delegate.findByIdCalls.get(), "@Cacheable이 아닌 타입은 항상 delegate로 조회되어야 한다");
    }

    @Test
    void transactionWriteInvalidatesSharedCache() {
        CountingOps delegate = new CountingOps(metadataFactory);
        delegate.seed(new Product(1L, "keyboard"));
        ReactiveEntityOperations ops = caching(delegate);

        ops.findById(Product.class, 1L).block();  // delegate #1 + 캐시 채움(트랜잭션 밖)

        // 트랜잭션 안에서 save → evict + commit 후 re-evict
        ops.inTransaction(txOps -> txOps.save(new Product(1L, "mouse"))).block();

        ops.findById(Product.class, 1L).block();  // 캐시 무효화되어 delegate #2
        assertEquals(2, delegate.findByIdCalls.get());
    }

    // --- fake delegate ------------------------------------------------------

    /**
     * DB 조회 횟수를 세는 in-memory {@link ReactiveEntityOperations}. store 키는
     * {@code EntityMetadata.readIdValue}로 뽑은 id 값이라 findById에 전달되는 id와 일치한다.
     */
    static final class CountingOps implements ReactiveEntityOperations {
        private final EntityMetadataFactory metadataFactory;
        private final Map<Object, Object> store = new ConcurrentHashMap<>();
        final AtomicInteger findByIdCalls = new AtomicInteger();

        CountingOps(EntityMetadataFactory metadataFactory) {
            this.metadataFactory = metadataFactory;
        }

        void seed(Object entity) {
            store.put(idOf(entity), entity);
        }

        @SuppressWarnings("unchecked")
        private Object idOf(Object entity) {
            return metadataFactory.getEntityMetadata((Class<Object>) entity.getClass()).readIdValue(entity);
        }

        @Override
        public <T> Mono<T> save(T entity) {
            store.put(idOf(entity), entity);
            return Mono.just(entity);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T, ID> Mono<T> findById(Class<T> entityType, ID id) {
            // 구독 시점에 카운트해야 캐시 히트로 구독되지 않은 경로가 잘못 세지지 않는다.
            return Mono.defer(() -> {
                findByIdCalls.incrementAndGet();
                Object value = store.get(id);
                return value == null ? Mono.empty() : Mono.just((T) value);
            });
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> Flux<T> findAll(Class<T> entityType, QuerySpec querySpec) {
            return Flux.fromIterable((Collection<T>) store.values());
        }

        @Override
        public <T> Mono<Long> delete(T entity) {
            return Mono.just(store.remove(idOf(entity)) != null ? 1L : 0L);
        }

        @Override
        public <T, ID> Mono<Long> deleteById(Class<T> entityType, ID id) {
            return Mono.just(store.remove(id) != null ? 1L : 0L);
        }

        @Override
        public <T> Mono<Long> count(Class<T> entityType, QuerySpec querySpec) {
            return Mono.just((long) store.size());
        }

        @Override
        public <T> Mono<Boolean> exists(Class<T> entityType, QuerySpec querySpec) {
            return Mono.just(!store.isEmpty());
        }

        @Override
        public <R> Mono<R> inTransaction(Function<ReactiveEntityOperations, Mono<R>> callback) {
            return callback.apply(this);
        }
    }

    // --- fixtures ----------------------------------------------------------

    @Entity
    @Table(name = "cache_product")
    @Cacheable
    static class Product {
        @Id
        private Long id;
        @Column(name = "name")
        private String name;

        Product() {
        }

        Product(Long id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    @Entity
    @Table(name = "cache_ledger")
    static class Ledger {
        @Id
        private Long id;
        @Column(name = "owner")
        private String owner;

        Ledger() {
        }

        Ledger(Long id, String owner) {
            this.id = id;
            this.owner = owner;
        }
    }
}
