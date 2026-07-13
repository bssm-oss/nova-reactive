package io.nova.cache;

import io.nova.core.ReactiveEntityOperations;
import io.nova.metadata.DefaultNamingStrategy;
import io.nova.metadata.EntityMetadataFactory;
import io.nova.query.Criteria;
import io.nova.query.NativeQuery;
import io.nova.query.QuerySpec;
import io.nova.query.Updater;
import jakarta.persistence.Cacheable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 쿼리 캐시 read-through와 write invalidation을, findAll 실행 횟수를 세는 in-memory fake delegate로 고립 검증한다.
 * {@link SimpleReactiveQueryCache}를 주입한(opt-in) 데코레이터만 대상으로 하며, 히트 시 delegate.findAll이
 * 재실행되지 않는지, write가 관련 타입의 쿼리 결과를 무효화하는지를 관찰한다.
 */
class QueryCacheDecoratorTest {

    private final EntityMetadataFactory metadataFactory =
            new EntityMetadataFactory(new DefaultNamingStrategy());

    private ReactiveEntityOperations cachingWithQuery(CountingQueryOps delegate) {
        return new CachingReactiveEntityOperations(
                delegate, metadataFactory, new CacheConfigurationResolver(),
                new SimpleReactiveCacheProvider(), new SimpleReactiveQueryCache());
    }

    private ReactiveEntityOperations cachingNoQuery(CountingQueryOps delegate) {
        return new CachingReactiveEntityOperations(
                delegate, metadataFactory, new CacheConfigurationResolver(), new SimpleReactiveCacheProvider());
    }

    @Test
    void secondIdenticalQueryHitsCacheNotDelegate() {
        CountingQueryOps delegate = new CountingQueryOps();
        delegate.seed(new Product(1L, "keyboard"));
        ReactiveEntityOperations ops = cachingWithQuery(delegate);
        QuerySpec spec = QuerySpec.empty().where(Criteria.eq("name", "keyboard"));

        assertEquals(1, ops.findAll(Product.class, spec).collectList().block().size());
        assertEquals(1, ops.findAll(Product.class, spec).collectList().block().size());

        assertEquals(1, delegate.findAllCalls.get(), "두 번째 동일 쿼리는 캐시 히트로 delegate.findAll을 재호출하지 않아야 한다");
    }

    @Test
    void differentSpecDoesNotShareCacheEntry() {
        CountingQueryOps delegate = new CountingQueryOps();
        delegate.seed(new Product(1L, "keyboard"));
        ReactiveEntityOperations ops = cachingWithQuery(delegate);

        ops.findAll(Product.class, QuerySpec.empty().where(Criteria.eq("name", "keyboard"))).collectList().block();
        ops.findAll(Product.class, QuerySpec.empty().where(Criteria.eq("name", "mouse"))).collectList().block();

        assertEquals(2, delegate.findAllCalls.get(), "다른 스펙은 서로 다른 캐시 키라 각각 delegate를 쳐야 한다");
    }

    @Test
    void saveInvalidatesQueryCacheForThatType() {
        CountingQueryOps delegate = new CountingQueryOps();
        delegate.seed(new Product(1L, "keyboard"));
        ReactiveEntityOperations ops = cachingWithQuery(delegate);
        QuerySpec spec = QuerySpec.empty().where(Criteria.eq("name", "keyboard"));

        ops.findAll(Product.class, spec).collectList().block();  // 미스 → delegate #1, 캐시 채움
        ops.save(new Product(2L, "keyboard")).block();            // write → 쿼리 캐시 무효화
        ops.findAll(Product.class, spec).collectList().block();  // 무효화되어 delegate #2

        assertEquals(2, delegate.findAllCalls.get(), "save는 대상 타입의 쿼리 결과를 무효화해 재조회를 유발해야 한다");
    }

    @Test
    void deleteInvalidatesQueryCache() {
        CountingQueryOps delegate = new CountingQueryOps();
        Product p = new Product(1L, "keyboard");
        delegate.seed(p);
        ReactiveEntityOperations ops = cachingWithQuery(delegate);
        QuerySpec spec = QuerySpec.empty();

        ops.findAll(Product.class, spec).collectList().block(); // delegate #1
        ops.delete(p).block();                                  // evict + 쿼리 무효화
        ops.findAll(Product.class, spec).collectList().block(); // delegate #2

        assertEquals(2, delegate.findAllCalls.get());
    }

    @Test
    void writeToOtherTypeDoesNotInvalidateUnrelatedQueryCache() {
        CountingQueryOps delegate = new CountingQueryOps();
        delegate.seed(new Product(1L, "keyboard"));
        delegate.seed(new Ledger(1L, "acme"));
        ReactiveEntityOperations ops = cachingWithQuery(delegate);
        QuerySpec spec = QuerySpec.empty();

        ops.findAll(Product.class, spec).collectList().block();  // delegate #1, Product 쿼리 캐시 채움
        ops.save(new Ledger(2L, "globex")).block();              // 다른(비캐시) 타입 write
        ops.findAll(Product.class, spec).collectList().block();  // Product 쿼리 캐시 여전히 히트

        assertEquals(1, delegate.findAllCalls.get(), "무관한 타입 write는 Product 쿼리 캐시를 건드리지 않아야 한다");
    }

    @Test
    void bulkDeleteByQueryInvalidatesQueryCache() {
        CountingQueryOps delegate = new CountingQueryOps();
        delegate.seed(new Product(1L, "keyboard"));
        ReactiveEntityOperations ops = cachingWithQuery(delegate);
        QuerySpec spec = QuerySpec.empty();

        ops.findAll(Product.class, spec).collectList().block();   // delegate #1
        ops.deleteAll(Product.class, QuerySpec.empty()).block();  // bulk delete → region + 쿼리 무효화
        ops.findAll(Product.class, spec).collectList().block();   // delegate #2

        assertEquals(2, delegate.findAllCalls.get());
    }

    @Test
    void nativeExecuteClearsQueryCache() {
        CountingQueryOps delegate = new CountingQueryOps();
        delegate.seed(new Product(1L, "keyboard"));
        ReactiveEntityOperations ops = cachingWithQuery(delegate);
        QuerySpec spec = QuerySpec.empty();

        ops.findAll(Product.class, spec).collectList().block();       // delegate #1
        ops.executeNative(NativeQuery.of("delete from cache_product")).block(); // 전역 clear
        ops.findAll(Product.class, spec).collectList().block();       // delegate #2

        assertEquals(2, delegate.findAllCalls.get());
    }

    @Test
    void transactionWriteInvalidatesQueryCacheAfterCommit() {
        CountingQueryOps delegate = new CountingQueryOps();
        delegate.seed(new Product(1L, "keyboard"));
        ReactiveEntityOperations ops = cachingWithQuery(delegate);
        QuerySpec spec = QuerySpec.empty();

        ops.findAll(Product.class, spec).collectList().block();   // delegate #1, 캐시 채움(트랜잭션 밖)
        ops.inTransaction(tx -> tx.save(new Product(2L, "mouse"))).block(); // 커밋 → 무효화
        ops.findAll(Product.class, spec).collectList().block();   // delegate #2

        assertEquals(2, delegate.findAllCalls.get());
    }

    @Test
    void inTransactionQueryIsNotPopulatedToSharedCache() {
        CountingQueryOps delegate = new CountingQueryOps();
        delegate.seed(new Product(1L, "keyboard"));
        ReactiveEntityOperations ops = cachingWithQuery(delegate);
        QuerySpec spec = QuerySpec.empty();

        ops.inTransaction(tx -> tx.findAll(Product.class, spec).collectList()).block(); // in-tx 조회(미채움)
        ops.findAll(Product.class, spec).collectList().block();  // 여전히 미스 → delegate #2 (그리고 채움)
        ops.findAll(Product.class, spec).collectList().block();  // 이제 히트

        assertEquals(2, delegate.findAllCalls.get(),
                "in-tx 조회가 공유 쿼리 캐시를 채웠다면 첫 non-tx 조회가 히트해 총 1이었을 것");
    }

    @Test
    void queryCacheDisabledByDefaultReExecutesEachTime() {
        CountingQueryOps delegate = new CountingQueryOps();
        delegate.seed(new Product(1L, "keyboard"));
        ReactiveEntityOperations ops = cachingNoQuery(delegate); // opt-in 안 함
        QuerySpec spec = QuerySpec.empty();

        ops.findAll(Product.class, spec).collectList().block();
        ops.findAll(Product.class, spec).collectList().block();

        assertEquals(2, delegate.findAllCalls.get(), "쿼리 캐시 미배선 시 findAll은 기존대로 매번 delegate를 쳐야 한다");
    }

    // --- 상속 계층 회귀: 다형 query-cache 키 충돌 방지 (CRITICAL 재발 방지) --------

    @Test
    void baseAndSubtypeQueriesDoNotCrossServe() {
        // @Cacheable을 root(Animal)에 선언 → Animal/Dog 모두 canonical keyType=Animal로 해석된다. delegate는
        // findAll(Animal)=전 행, findAll(Dog)=isInstance 부분집합으로 서로 다른 결과셋을 낸다. read 키가
        // canonical keyType으로 만들어지면 둘이 같은 키를 공유해 교차 서빙(잘못된 결과/ClassCastException)된다.
        // 실제 쿼리 타입으로 키를 만들면 각자 올바른 결과셋을 받아야 한다.
        CountingQueryOps delegate = new CountingQueryOps();
        delegate.seed(new Animal(1L, "generic"));
        delegate.seed(new Dog(2L, "rex"));
        ReactiveEntityOperations ops = cachingWithQuery(delegate);
        QuerySpec all = QuerySpec.empty();

        List<Animal> animals = ops.findAll(Animal.class, all).collectList().block(); // 캐시: 전 행(2)
        List<Dog> dogs = ops.findAll(Dog.class, all).collectList().block();          // 별도 키 → 부분집합(1)

        assertEquals(2, animals.size(), "findAll(Animal)은 전 계층 행을 반환해야 한다");
        assertEquals(1, dogs.size(), "findAll(Dog)은 base 결과를 교차 서빙받지 않고 Dog만 반환해야 한다");
        assertInstanceOf(Dog.class, dogs.get(0), "교차 서빙되면 여기서 Animal 인스턴스거나 CCE가 난다");
        assertEquals(2, delegate.findAllCalls.get(),
                "Base/Sub 쿼리는 서로 다른 키라 각각 delegate를 실행해야 한다(false hit 없음)");

        // 재실행은 각 타입별로 히트해야 한다(추가 delegate 호출 없음).
        assertEquals(2, ops.findAll(Animal.class, all).collectList().block().size());
        assertEquals(1, ops.findAll(Dog.class, all).collectList().block().size());
        assertEquals(2, delegate.findAllCalls.get(), "동일 타입 재조회는 각자 캐시 히트여야 한다");
    }

    @Test
    void subtypeWriteInvalidatesBaseTypeQuery() {
        // subtype write는 canonical keyType(Animal) 파티션을 통째 무효화하므로 base-type query도 재조회돼야 한다.
        CountingQueryOps delegate = new CountingQueryOps();
        delegate.seed(new Animal(1L, "generic"));
        ReactiveEntityOperations ops = cachingWithQuery(delegate);
        QuerySpec all = QuerySpec.empty();

        ops.findAll(Animal.class, all).collectList().block();  // delegate #1, Animal 파티션 캐시
        ops.save(new Dog(2L, "rex")).block();                  // subtype write → Animal 파티션 무효화
        ops.findAll(Animal.class, all).collectList().block();  // 무효화되어 delegate #2

        assertTrue(delegate.findAllCalls.get() == 2,
                "subtype write는 canonical 파티션을 비워 base-type query를 재조회시켜야 한다");
    }

    // --- fake delegate ------------------------------------------------------

    final class CountingQueryOps implements ReactiveEntityOperations {
        private final Map<Object, Object> store = new ConcurrentHashMap<>();
        final AtomicInteger findAllCalls = new AtomicInteger();

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
        public <T> Flux<T> findAll(Class<T> entityType, QuerySpec querySpec) {
            // 구독 시점에 카운트 — 캐시 히트로 구독되지 않으면 세지 않는다.
            return Flux.defer(() -> {
                findAllCalls.incrementAndGet();
                List<T> matching = new ArrayList<>();
                for (Object v : store.values()) {
                    if (entityType.isInstance(v)) {
                        matching.add((T) v);
                    }
                }
                return Flux.fromIterable(matching);
            });
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T, ID> Mono<T> findById(Class<T> entityType, ID id) {
            return Mono.defer(() -> {
                Object value = store.get(id);
                return value == null ? Mono.empty() : Mono.just((T) value);
            });
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
        public <T> Mono<Long> deleteAll(Class<T> entityType, QuerySpec querySpec) {
            return Mono.just(0L);
        }

        @Override
        public <T> Mono<Long> update(Class<T> entityType, Updater<T> updater) {
            return Mono.just(0L);
        }

        @Override
        public Mono<Long> executeNative(NativeQuery query) {
            return Mono.just(0L);
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

    /**
     * 상속 계층 root. {@code @Cacheable}은 여기에만 선언되므로 Animal/Dog 모두 canonical keyType은 Animal이다.
     */
    @Entity
    @Table(name = "cache_animal")
    @Cacheable
    static class Animal {
        @Id
        private Long id;
        @Column(name = "species")
        private String species;

        Animal() {
        }

        Animal(Long id, String species) {
            this.id = id;
            this.species = species;
        }
    }

    @Entity
    @Table(name = "cache_animal")
    static class Dog extends Animal {
        Dog() {
        }

        Dog(Long id, String species) {
            super(id, species);
        }
    }
}
