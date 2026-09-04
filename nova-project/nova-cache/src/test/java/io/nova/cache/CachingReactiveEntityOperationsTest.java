package io.nova.cache;

import io.nova.core.ReactiveEntityOperations;
import io.nova.metadata.DefaultNamingStrategy;
import io.nova.metadata.EntityMetadataFactory;
import io.nova.query.NativeQuery;
import io.nova.query.QuerySpec;
import io.nova.query.Updater;
import jakarta.persistence.Access;
import jakarta.persistence.AccessType;
import jakarta.persistence.Cacheable;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Converter;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    private ReactiveEntityOperations caching(CountingOps delegate, SimpleReactiveCacheProvider provider) {
        return new CachingReactiveEntityOperations(
                delegate, metadataFactory, new CacheConfigurationResolver(), provider);
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
    void firstHitMutationDoesNotContaminateMappedPropertyAssociationCollectionOrCycle() {
        CountingOps delegate = new CountingOps(metadataFactory);
        CachedGraphRoot source = new CachedGraphRoot(1L, "root");
        CachedGraphChild child = new CachedGraphChild(2L, "child");
        source.children.add(child);
        child.setOwner(source);
        delegate.seed(source);
        ReactiveEntityOperations ops = caching(delegate);
        AtomicReference<CachedGraphRoot> first = new AtomicReference<>();

        StepVerifier.create(ops.findById(CachedGraphRoot.class, 1L))
                .assertNext(first::set)
                .verifyComplete();
        first.get().name = "mutated";
        first.get().children.get(0).setName("mutated child");
        first.get().children.clear();

        StepVerifier.create(ops.findById(CachedGraphRoot.class, 1L))
                .assertNext(hit -> {
                    assertNotSame(first.get(), hit);
                    assertEquals("root", hit.name);
                    assertEquals(1, hit.children.size());
                    assertEquals("child", hit.children.get(0).getName());
                    assertSame(hit, hit.children.get(0).getOwner());
                })
                .verifyComplete();
        assertEquals(1, delegate.findByIdCalls.get(), "second lookup must be served from the isolated cache snapshot");
    }

    @Test
    void convertedRecordIsReconstructedForEachCacheHit() {
        CountingOps delegate = new CountingOps(metadataFactory);
        delegate.seed(new ConvertedRecordEntity(3L, new CacheCode("blue")));
        ReactiveEntityOperations ops = caching(delegate);
        AtomicReference<ConvertedRecordEntity> first = new AtomicReference<>();

        StepVerifier.create(ops.findById(ConvertedRecordEntity.class, 3L))
                .assertNext(first::set)
                .verifyComplete();
        StepVerifier.create(ops.findById(ConvertedRecordEntity.class, 3L))
                .assertNext(hit -> {
                    assertEquals(new CacheCode("blue"), hit.code);
                    assertNotSame(first.get().code, hit.code);
                })
                .verifyComplete();
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
    void writeToNonCacheableTypeClearsEveryEntityRegion() {
        CountingOps delegate = new CountingOps(metadataFactory);
        delegate.seed(new Product(1L, "keyboard"));
        SimpleReactiveCacheProvider provider = new SimpleReactiveCacheProvider();
        ReactiveEntityOperations ops = caching(delegate, provider);
        String productRegion = new CacheConfigurationResolver().resolve(Product.class).region();

        StepVerifier.create(ops.findById(Product.class, 1L)).expectNextCount(1).verifyComplete();
        assertEquals(1, provider.getCache(productRegion).size());
        StepVerifier.create(ops.save(new Ledger(2L, "acme"))).expectNextCount(1).verifyComplete();

        assertEquals(0, provider.getCache(productRegion).size(),
                "a non-cacheable associated write must invalidate graph-bearing cache entries");
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

    @Test
    void polymorphicWriteInvalidatesBaseTypeCacheKey() {
        // findById(Animal.class, 1)이 런타임 Dog를 로드해 캐시하고, save(dog)/delete(dog)가 런타임 타입으로
        // evict 해도 canonical 키 정규화 덕분에 같은 엔트리를 무효화해야 한다(다형 stale read 방지).
        CountingOps delegate = new CountingOps(metadataFactory);
        delegate.seed(new Dog(1L, "rex")); // store[1] = Dog 인스턴스
        ReactiveEntityOperations ops = caching(delegate);

        Animal loaded = ops.findById(Animal.class, 1L).block(); // 선언 타입 Animal → delegate #1 + 캐시(canonical 키)
        assertTrue(loaded instanceof Dog);
        ops.findById(Animal.class, 1L).block();                 // 캐시 히트(여전히 #1)

        ops.save(new Dog(1L, "max")).block();                    // 런타임 타입 write → canonical 키로 evict
        ops.findById(Animal.class, 1L).block();                 // 무효화되어 delegate #2

        assertEquals(2, delegate.findByIdCalls.get(),
                "다형 write는 base-type findById가 심은 canonical 키를 무효화해야 한다");
    }

    @Test
    void polymorphicWriteWithRedeclaredCacheableStillInvalidatesBaseKey() {
        // subtype(Cat)에 @Cacheable을 재선언해도 canonical은 root(Animal)이어야 한다. base-type findById가
        // 심은 키를 subtype save가 evict 못 하면 stale Cat이 서빙된다(재리뷰 MEDIUM 재발 경로).
        CountingOps delegate = new CountingOps(metadataFactory);
        delegate.seed(new Cat(1L, "tabby"));
        ReactiveEntityOperations ops = caching(delegate);

        ops.findById(Animal.class, 1L).block(); // delegate #1 + 캐시(canonical Animal 키)
        ops.findById(Animal.class, 1L).block(); // 히트(#1)

        ops.save(new Cat(1L, "siamese")).block(); // 재선언 subtype write → canonical 키로 evict
        ops.findById(Animal.class, 1L).block();  // 무효화되어 delegate #2

        assertEquals(2, delegate.findByIdCalls.get(),
                "@Cacheable 재선언 subtype write도 root canonical 키를 무효화해야 한다");
    }

    @Test
    void polymorphicDeleteInvalidatesBaseTypeCacheKey() {
        CountingOps delegate = new CountingOps(metadataFactory);
        Dog dog = new Dog(1L, "rex");
        delegate.seed(dog);
        ReactiveEntityOperations ops = caching(delegate);

        ops.findById(Animal.class, 1L).block();  // delegate #1 + 캐시
        ops.delete(dog).block();                 // 런타임 타입 delete → canonical 키 evict
        ops.findById(Animal.class, 1L).block();  // delegate #2

        assertEquals(2, delegate.findByIdCalls.get());
    }

    @Test
    void inTransactionReadDoesNotPopulateSharedCache() {
        // 트랜잭션 안 findById는 미커밋 읽기 유출 방지를 위해 공유 캐시를 채우지 않아야 한다.
        CountingOps delegate = new CountingOps(metadataFactory);
        delegate.seed(new Product(1L, "keyboard"));
        ReactiveEntityOperations ops = caching(delegate);

        ops.inTransaction(tx -> tx.findById(Product.class, 1L)).block(); // delegate #1, 캐시 미채움
        ops.findById(Product.class, 1L).block();  // 캐시에 없으므로 delegate #2 (그리고 채움)
        ops.findById(Product.class, 1L).block();  // 이제 히트, delegate 호출 없음

        assertEquals(2, delegate.findByIdCalls.get(),
                "in-tx 읽기가 캐시를 채웠다면 첫 non-tx findById가 히트해 총 1이었을 것");
    }

    @Test
    void bulkDeleteByQueryClearsRegion() {
        CountingOps delegate = new CountingOps(metadataFactory);
        delegate.seed(new Product(1L, "keyboard"));
        SimpleReactiveCacheProvider provider = new SimpleReactiveCacheProvider();
        ReactiveEntityOperations ops = caching(delegate, provider);
        String region = new CacheConfigurationResolver().resolve(Product.class).region();

        ops.findById(Product.class, 1L).block();  // 캐시 채움
        assertEquals(1, provider.getCache(region).size());

        ops.deleteAll(Product.class, QuerySpec.empty()).block(); // bulk delete → region clear
        assertEquals(0, provider.getCache(region).size(), "bulk delete는 대상 region을 비워야 한다");
    }

    @Test
    void bulkUpdateByUpdaterClearsRegion() {
        CountingOps delegate = new CountingOps(metadataFactory);
        delegate.seed(new Product(1L, "keyboard"));
        SimpleReactiveCacheProvider provider = new SimpleReactiveCacheProvider();
        ReactiveEntityOperations ops = caching(delegate, provider);
        String region = new CacheConfigurationResolver().resolve(Product.class).region();

        ops.findById(Product.class, 1L).block();
        assertEquals(1, provider.getCache(region).size());

        ops.update(Product.class, (Updater<Product>) null).block(); // bulk update → region clear (fake는 인자 무시)
        assertEquals(0, provider.getCache(region).size(), "bulk update는 대상 region을 비워야 한다");
    }

    @Test
    void nativeExecuteClearsAllRegions() {
        CountingOps delegate = new CountingOps(metadataFactory);
        delegate.seed(new Product(1L, "keyboard"));
        delegate.seed(new Animal(2L, "generic"));
        SimpleReactiveCacheProvider provider = new SimpleReactiveCacheProvider();
        ReactiveEntityOperations ops = caching(delegate, provider);
        CacheConfigurationResolver r = new CacheConfigurationResolver();
        String productRegion = r.resolve(Product.class).region();
        String animalRegion = r.resolve(Animal.class).region();

        ops.findById(Product.class, 1L).block();
        ops.findById(Animal.class, 2L).block();
        assertEquals(1, provider.getCache(productRegion).size());
        assertEquals(1, provider.getCache(animalRegion).size());

        ops.executeNative(NativeQuery.of("delete from cache_product")).block(); // 대상 불명 → 전역 clear
        assertEquals(0, provider.getCache(productRegion).size());
        assertEquals(0, provider.getCache(animalRegion).size());
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

        // bulk/native 경로: 실제 DB 없이 count만 돌려준다(인자는 무시). 데코레이터의 무효화 side-effect만 검증한다.
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
    @Table(name = "cache_converted_record")
    @Cacheable
    static class ConvertedRecordEntity {
        @Id
        private Long id;
        @Convert(converter = CacheCodeConverter.class)
        private CacheCode code;

        ConvertedRecordEntity() {
        }

        ConvertedRecordEntity(Long id, CacheCode code) {
            this.id = id;
            this.code = code;
        }
    }

    record CacheCode(String value) {
    }

    @Converter
    public static class CacheCodeConverter implements jakarta.persistence.AttributeConverter<CacheCode, String> {
        @Override
        public String convertToDatabaseColumn(CacheCode attribute) {
            return attribute == null ? null : attribute.value();
        }

        @Override
        public CacheCode convertToEntityAttribute(String databaseValue) {
            return databaseValue == null ? null : new CacheCode(databaseValue);
        }
    }

    @Entity
    @Table(name = "cache_graph_root")
    @Cacheable
    static class CachedGraphRoot {
        @Id
        private Long id;
        private String name;
        @OneToMany(mappedBy = "owner")
        private List<CachedGraphChild> children = new ArrayList<>();

        CachedGraphRoot() {
        }

        CachedGraphRoot(Long id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    @Entity
    @Table(name = "cache_graph_child")
    @Access(AccessType.PROPERTY)
    static class CachedGraphChild {
        private Long id;
        private String name;
        private CachedGraphRoot owner;

        CachedGraphChild() {
        }

        CachedGraphChild(Long id, String name) {
            this.id = id;
            this.name = name;
        }

        @Id
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
        CachedGraphRoot getOwner() {
            return owner;
        }

        void setOwner(CachedGraphRoot owner) {
            this.owner = owner;
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
     * 다형 캐시 키 정규화 검증용 base 타입. {@code @Cacheable}은 여기(root)에만 선언되므로 canonical keyType은
     * 항상 {@code Animal}이다.
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

    /**
     * {@code @Cacheable}을 subtype에 <b>재선언</b>한 케이스. nearest-declaring 해석이면 canonical이 Cat으로
     * 갈라져 base-type findById 캐시를 evict 못 하지만, root-most 정규화면 canonical은 여전히 Animal이다.
     */
    @Entity
    @Table(name = "cache_animal")
    @Cacheable
    static class Cat extends Animal {
        Cat() {
        }

        Cat(Long id, String species) {
            super(id, species);
        }
    }
}
