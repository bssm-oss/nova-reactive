package io.nova.cache;

import io.nova.cache.spi.CacheKey;
import io.nova.cache.spi.ReactiveCache;
import io.nova.cache.spi.ReactiveCacheProvider;
import io.nova.cache.spi.ReactiveQueryCache;
import io.nova.core.ReactiveEntityOperations;
import io.nova.core.RowAccessor;
import io.nova.fetch.FetchGroup;
import io.nova.metadata.EntityMetadataFactory;
import io.nova.query.AggregateRow;
import io.nova.query.AggregateSpec;
import io.nova.query.LockMode;
import io.nova.query.NativeQuery;
import io.nova.query.Page;
import io.nova.query.Pageable;
import io.nova.query.Projection;
import io.nova.query.QuerySpec;
import io.nova.query.Slice;
import io.nova.query.Updater;
import io.nova.sql.CompiledQuery;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * {@link ReactiveEntityOperations}를 감싸 2차 캐시(read-through + write invalidation)를 더하는 데코레이터.
 * 기존 {@code SimpleReactiveEntityOperations}/{@code PersistenceSession} 내부를 전혀 수정하지 않고 바깥에서
 * 캐시를 배선한다(격리 우선 — hub 파일 무수정).
 *
 * <h2>동작</h2>
 * <ul>
 *   <li><b>read-through</b>: {@code findById(Class, id)}가 캐시 히트면 DB를 우회하고, 미스면 delegate로
 *       조회한 뒤 {@code @Cacheable} 엔티티를 캐시에 채운다.</li>
 *   <li><b>write invalidation</b>: {@code save}/{@code update}/{@code delete}(및 batch/변형)는 delegate 실행
 *       후 해당 엔티티 캐시 엔트리를 즉시 evict 한다. 대상 행을 특정할 수 없는 bulk update/delete와
 *       compiled/native write는 보수적으로 region(또는 전체)을 비운다.</li>
 *   <li><b>query cache read-through(opt-in)</b>: {@link ReactiveQueryCache}를 주입하면
 *       {@code findAll(Class, QuerySpec)} 결과를 정규화된 스펙 키로 캐시한다(히트 시 0 SQL). 어떤 write든 대상
 *       엔티티 타입의 쿼리 결과를 <b>통째로 무효화</b>한다(보수적 정합성). 쿼리 캐시가 없으면(기본) 이 경로는
 *       기존과 동일하게 delegate로 통과하고 조회 엔티티로 엔티티 캐시만 warming 한다 — 기본 동작 무변경.</li>
 * </ul>
 *
 * <h2>정합성 계약 (v1)</h2>
 * <ul>
 *   <li>캐시는 <b>읽기에서만 채워지고 쓰기에서는 무효화만</b> 된다 — 미커밋 write 값을 캐시에 넣지 않는다.
 *       따라서 rollback 되는 트랜잭션은 캐시 <i>제거</i>만 유발하며(최악의 경우 다음 조회가 캐시 미스),
 *       stale 값을 <i>주입</i>하지 않는다.</li>
 *   <li>트랜잭션 스코프({@link #inTransaction}) 안에서는 read를 캐시에 채우지 않고(미커밋 읽기 유출 방지)
 *       write는 즉시 evict 한다. 추가로 commit 성공 후 무효화를 재적용해(post-commit re-evict) 동시 reader가
 *       옛 값을 되채운 창을 좁힌다.</li>
 *   <li>알려진 한계: 단일 JVM in-process 캐시로, 동시 writer 간 완전한 트랜잭셔널 정합성(외부 post-commit
 *       broadcast)은 외부 캐시 프로바이더에서 다룬다. {@code findById(..., FetchGroup)}, projection/paged/slice
 *       조회, count/exists 스칼라, native/compiled 조회 결과는 캐시하지 않는다(자식 hydration 편차·범위 위험
 *       회피). 쿼리 캐시는 {@code findAll(Class, QuerySpec)} 엔티티 결과에만 적용되며, {@link ReactiveQueryCache}를
 *       주입한 경우에만(opt-in) 활성화된다.</li>
 *   <li><b>배선 경계(EntityManager 결합):</b> {@code ReactiveEntityManager}는 반드시 이 캐시 데코레이터
 *       <b>위에</b> 얹어라(예: {@code new SimpleReactiveEntityManager(NovaCache.caching(base, ...), mf)}).
 *       그래야 EM의 persist/merge/remove가 이 데코레이터의 write invalidation 경로를 거친다. EM을 캐시되지
 *       않은 <b>base operations 위에</b> 만들고 <em>별도의</em> 캐시 데코레이터로 읽으면, EM write가 무효화를
 *       우회해 그 캐시가 stale 값을 낼 수 있다. 또한 {@link #flush()}는 세션 dirty를 DB로 내보낼 뿐 캐시를
 *       갱신하지 않으므로, 세션 안에서 dirty만 mutate하고 write 메서드({@code save}/{@code update}/{@code delete})를
 *       거치지 않은 변경 역시 캐시 무효화를 트리거하지 않는다 — 캐시와 EM은 같은 데코레이터 스택으로 결합해
 *       배선해야 정합적이다.</li>
 * </ul>
 *
 * <p>{@code @Cacheable}이 아닌 타입은 캐시 없이 그대로 delegate로 통과한다 — 기존 리액티브 동작과 동일하다.
 */
public final class CachingReactiveEntityOperations implements ReactiveEntityOperations {

    private final ReactiveEntityOperations delegate;
    private final EntityMetadataFactory metadataFactory;
    private final CacheConfigurationResolver resolver;
    private final ReactiveCacheProvider provider;
    /** 쿼리 결과 캐시(opt-in). {@code null}이면 쿼리 캐싱 비활성 — {@code findAll(Class, QuerySpec)}은 기존 통과. */
    private final ReactiveQueryCache queryCache;
    /** 읽기 결과를 캐시에 채울지 여부. 트랜잭션 스코프 내부에서는 {@code false}(미커밋 유출 방지). */
    private final boolean populateOnRead;
    /** 트랜잭션 스코프에서만 non-null. write 무효화를 기록해 commit 후 재적용한다. */
    private final TransactionEvictionBuffer evictionBuffer;

    public CachingReactiveEntityOperations(
            ReactiveEntityOperations delegate,
            EntityMetadataFactory metadataFactory,
            CacheConfigurationResolver resolver,
            ReactiveCacheProvider provider) {
        this(delegate, metadataFactory, resolver, provider, null, true, null);
    }

    /**
     * 쿼리 결과 캐시를 함께 배선하는 생성자. {@code queryCache}가 {@code null}이면 4-인자 생성자와 동일하게
     * 쿼리 캐싱을 비활성화한다(opt-in).
     */
    public CachingReactiveEntityOperations(
            ReactiveEntityOperations delegate,
            EntityMetadataFactory metadataFactory,
            CacheConfigurationResolver resolver,
            ReactiveCacheProvider provider,
            ReactiveQueryCache queryCache) {
        this(delegate, metadataFactory, resolver, provider, queryCache, true, null);
    }

    private CachingReactiveEntityOperations(
            ReactiveEntityOperations delegate,
            EntityMetadataFactory metadataFactory,
            CacheConfigurationResolver resolver,
            ReactiveCacheProvider provider,
            ReactiveQueryCache queryCache,
            boolean populateOnRead,
            TransactionEvictionBuffer evictionBuffer) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.metadataFactory = Objects.requireNonNull(metadataFactory, "metadataFactory must not be null");
        this.resolver = Objects.requireNonNull(resolver, "resolver must not be null");
        this.provider = Objects.requireNonNull(provider, "provider must not be null");
        this.queryCache = queryCache; // nullable — opt-in
        this.populateOnRead = populateOnRead;
        this.evictionBuffer = evictionBuffer;
    }

    private CachingReactiveEntityOperations withDelegate(
            ReactiveEntityOperations inner, boolean populate, TransactionEvictionBuffer buffer) {
        return new CachingReactiveEntityOperations(
                inner, metadataFactory, resolver, provider, queryCache, populate, buffer);
    }

    // --- read-through ------------------------------------------------------

    @Override
    @SuppressWarnings("unchecked")
    public <T, ID> Mono<T> findById(Class<T> entityType, ID id) {
        CacheConfiguration config = resolver.resolve(entityType);
        if (!config.cacheable() || id == null) {
            return delegate.findById(entityType, id);
        }
        // 키 타입은 canonical(정규) 타입으로 — 다형 findById(base)와 save(subtype)가 같은 키를 공유하게 한다.
        CacheKey key = new CacheKey(config.region(), config.keyType(), id);
        ReactiveCache cache = provider.getCache(config.region());
        Mono<T> load = delegate.findById(entityType, id)
                .flatMap(loaded -> populateOnRead
                        ? cache.put(key, loaded).thenReturn(loaded)
                        : Mono.just(loaded));
        return cache.get(key).map(value -> (T) value).switchIfEmpty(load);
    }

    @Override
    public <T, ID> Mono<Boolean> existsById(Class<T> entityType, ID id) {
        CacheConfiguration config = resolver.resolve(entityType);
        if (!config.cacheable() || id == null) {
            return delegate.existsById(entityType, id);
        }
        CacheKey key = new CacheKey(config.region(), config.keyType(), id);
        return provider.getCache(config.region()).get(key)
                .map(value -> Boolean.TRUE)
                .switchIfEmpty(delegate.existsById(entityType, id));
    }

    @Override
    public <T, ID> Flux<T> findAllById(Class<T> entityType, Iterable<ID> ids) {
        CacheConfiguration config = resolver.resolve(entityType);
        Flux<T> result = delegate.findAllById(entityType, ids);
        if (!config.cacheable() || !populateOnRead) {
            return result;
        }
        return result.concatMap(entity -> putEntity(entity).thenReturn(entity));
    }

    @Override
    public <T> Flux<T> findAll(Class<T> entityType, QuerySpec querySpec) {
        CacheConfiguration config = resolver.resolve(entityType);
        boolean warmEntityCache = config.cacheable() && populateOnRead;
        // 쿼리 캐시 read-through: opt-in(queryCache != null) + cacheable + 트랜잭션 밖(populateOnRead)
        // + 잠금 없는 쿼리만. 잠금(FOR UPDATE/SHARE)은 항상 DB를 쳐야 하므로 캐시하지 않는다.
        if (queryCache != null && warmEntityCache && querySpec.lockMode() == LockMode.NONE) {
            Class<?> type = config.keyType();
            String key = QuerySpecCacheKey.of(type, querySpec);
            Flux<T> onMiss = Flux.defer(() -> delegate.findAll(entityType, querySpec)
                    .collectList()
                    .flatMapMany(list -> queryCache.put(type, key, new ArrayList<Object>(list))
                            // 결과를 쿼리 캐시에 저장 + 엔티티 캐시도 warming(이후 findById 히트).
                            .thenMany(Flux.fromIterable(list))
                            .concatMap(entity -> putEntity(entity).thenReturn(entity))));
            // 빈 리스트도 히트로 취급해야 하므로 Mono 존재 여부로 hit/miss를 판별한다(빈 Flux를
            // switchIfEmpty로 miss 처리하면 빈 결과가 매번 재실행됨).
            return queryCache.get(type, key)
                    .map(Optional::of)
                    .defaultIfEmpty(Optional.empty())
                    .flatMapMany(hit -> hit.isPresent()
                            ? Flux.fromIterable(hit.get()).map(CachingReactiveEntityOperations::castEntity)
                            : onMiss);
        }
        Flux<T> result = delegate.findAll(entityType, querySpec);
        if (!warmEntityCache) {
            return result;
        }
        // 조회된 엔티티를 캐시에 채워 이후 findById가 히트하도록 한다(쿼리 캐시 미배선 시 결과 자체는 캐시 안 함).
        return result.concatMap(entity -> putEntity(entity).thenReturn(entity));
    }

    @Override
    public <E, P> Flux<P> findAll(Projection<E, P> projection, QuerySpec querySpec) {
        return delegate.findAll(projection, querySpec);
    }

    @Override
    public <T> Mono<Page<T>> findAll(Class<T> entityType, QuerySpec querySpec, Pageable pageable) {
        return delegate.findAll(entityType, querySpec, pageable);
    }

    @Override
    public <T> Mono<Slice<T>> findSlice(Class<T> entityType, QuerySpec querySpec, Pageable pageable) {
        return delegate.findSlice(entityType, querySpec, pageable);
    }

    @Override
    public <T> Mono<Long> count(Class<T> entityType, QuerySpec querySpec) {
        return delegate.count(entityType, querySpec);
    }

    @Override
    public <T> Mono<Boolean> exists(Class<T> entityType, QuerySpec querySpec) {
        return delegate.exists(entityType, querySpec);
    }

    @Override
    public <P> Mono<P> findById(Class<P> entityType, Object id, FetchGroup<P> fetchGroup) {
        // FetchGroup 경로는 자식 hydration이 달라질 수 있어 v1에서는 캐시를 우회한다(read/put 모두 없음).
        return delegate.findById(entityType, id, fetchGroup);
    }

    @Override
    public <P> Flux<P> findAll(Class<P> entityType, FetchGroup<P> fetchGroup) {
        return delegate.findAll(entityType, fetchGroup);
    }

    @Override
    public <T> Flux<T> findAll(Class<T> entityType, CompiledQuery query, Object... bindings) {
        return delegate.findAll(entityType, query, bindings);
    }

    // --- write invalidation -------------------------------------------------

    @Override
    public <T> Mono<T> save(T entity) {
        return delegate.save(entity).flatMap(saved -> invalidateEntity(saved).thenReturn(saved));
    }

    @Override
    public <T> Mono<T> update(T entity, Iterable<String> fields) {
        return delegate.update(entity, fields).flatMap(updated -> invalidateEntity(updated).thenReturn(updated));
    }

    @Override
    public <T> Flux<T> saveAll(Iterable<T> entities) {
        List<T> list = toList(entities);
        return delegate.saveAll(list).concatMap(saved -> invalidateEntity(saved).thenReturn(saved));
    }

    @Override
    public <T> Mono<Long> delete(T entity) {
        return delegate.delete(entity).flatMap(count -> invalidateEntity(entity).thenReturn(count));
    }

    @Override
    public <T, ID> Mono<Long> deleteById(Class<T> entityType, ID id) {
        return delegate.deleteById(entityType, id).flatMap(count -> invalidateKey(entityType, id).thenReturn(count));
    }

    @Override
    public <T> Mono<Long> deleteAll(Iterable<T> entities) {
        List<T> list = toList(entities);
        return delegate.deleteAll(list)
                .flatMap(count -> Flux.fromIterable(list)
                        .concatMap(this::invalidateEntity)
                        .then()
                        .thenReturn(count));
    }

    @Override
    public <T, ID> Mono<Long> deleteAllById(Class<T> entityType, Iterable<ID> ids) {
        List<ID> list = toList(ids);
        return delegate.deleteAllById(entityType, list)
                .flatMap(count -> Flux.fromIterable(list)
                        .concatMap(id -> invalidateKey(entityType, id))
                        .then()
                        .thenReturn(count));
    }

    @Override
    public <T> Mono<Long> deleteAll(Class<T> entityType, QuerySpec querySpec) {
        // predicate로 지운 행을 특정할 수 없어 해당 타입 region을 통째로 비운다(보수적).
        return delegate.deleteAll(entityType, querySpec)
                .flatMap(count -> invalidateRegion(entityType).thenReturn(count));
    }

    @Override
    public <T> Mono<Long> update(Class<T> entityType, Updater<T> updater) {
        // bulk update도 대상 행을 특정할 수 없어 region을 통째로 비운다.
        return delegate.update(entityType, updater)
                .flatMap(count -> invalidateRegion(entityType).thenReturn(count));
    }

    // --- native / compiled: 대상 불명 → 보수적 전역 무효화 ----------------------

    @Override
    public Mono<Long> executeNative(NativeQuery query) {
        return delegate.executeNative(query)
                .flatMap(count -> provider.clearAll().then(clearQueries()).thenReturn(count));
    }

    @Override
    public Mono<Long> execute(CompiledQuery query, Object... bindings) {
        return delegate.execute(query, bindings)
                .flatMap(count -> provider.clearAll().then(clearQueries()).thenReturn(count));
    }

    @Override
    public <T> Flux<T> queryNative(NativeQuery query, Function<RowAccessor, T> mapper) {
        return delegate.queryNative(query, mapper);
    }

    @Override
    public <T> Mono<T> queryNativeOne(NativeQuery query, Function<RowAccessor, T> mapper) {
        return delegate.queryNativeOne(query, mapper);
    }

    @Override
    public <T> Flux<AggregateRow> aggregate(Class<T> entityType, AggregateSpec spec) {
        return delegate.aggregate(entityType, spec);
    }

    // --- session flush ------------------------------------------------------

    /**
     * 현재 Reactor {@code Context}에 바인딩된 세션의 보류 변경을 즉시 DB로 밀어낸다. 이 데코레이터는 세션을
     * 소유하지 않으므로 감싼 delegate({@code SimpleReactiveEntityOperations})의 flush로 그대로 위임한다.
     * <p>
     * {@link ReactiveEntityOperations#flush()}의 기본 구현은 no-op이므로 이 메서드를 override하지 않으면,
     * 이 캐시 데코레이터 <b>위에</b> 얹은 {@code ReactiveEntityManager.flush()}가 조용히 무시(silent no-op)돼
     * 세션 dirty가 명시적으로 flush되지 않는 배선 함정이 생긴다. 따라서 delegate.flush()로 위임해 그 표면을
     * 보존한다. flush는 세션 상태만 내보내며 캐시는 건드리지 않는다(무효화는 write 경로 담당 — 클래스 javadoc의
     * 배선 경계 참고).
     */
    @Override
    public Mono<Void> flush() {
        return delegate.flush();
    }

    // --- transaction / read session scoping --------------------------------

    @Override
    public <R> Mono<R> inTransaction(Function<ReactiveEntityOperations, Mono<R>> callback) {
        TransactionEvictionBuffer buffer = new TransactionEvictionBuffer();
        Mono<R> body = delegate.inTransaction(inner -> callback.apply(withDelegate(inner, false, buffer)));
        // commit 성공 후 무효화 재적용(post-commit re-evict). 값이 없거나 있어도 반드시 한 번 flush.
        return body.flatMap(result -> buffer.flush(provider, queryCache).thenReturn(result))
                .switchIfEmpty(Mono.defer(() -> buffer.flush(provider, queryCache).then(Mono.empty())));
    }

    @Override
    public <R> Mono<R> inReadSession(Function<ReactiveEntityOperations, Mono<R>> callback) {
        return delegate.inReadSession(inner ->
                callback.apply(withDelegate(inner, populateOnRead, evictionBuffer)));
    }

    // --- invalidation helpers ----------------------------------------------

    private Mono<Void> invalidateEntity(Object entity) {
        if (entity == null) {
            return Mono.empty();
        }
        CacheConfiguration config = resolver.resolve(entity.getClass());
        if (!config.cacheable()) {
            return Mono.empty();
        }
        Object id = metadataFactory.getEntityMetadata(cast(entity.getClass())).readIdValue(entity);
        if (id == null) {
            return Mono.empty();
        }
        // canonical 키 타입으로 evict — findById(base)가 심은 키와 일치시켜 다형 stale read를 막는다.
        return invalidate(new CacheKey(config.region(), config.keyType(), id));
    }

    private Mono<Void> putEntity(Object entity) {
        if (entity == null) {
            return Mono.empty();
        }
        CacheConfiguration config = resolver.resolve(entity.getClass());
        if (!config.cacheable()) {
            return Mono.empty();
        }
        Object id = metadataFactory.getEntityMetadata(cast(entity.getClass())).readIdValue(entity);
        if (id == null) {
            return Mono.empty();
        }
        return provider.getCache(config.region()).put(new CacheKey(config.region(), config.keyType(), id), entity);
    }

    private Mono<Void> invalidateKey(Class<?> entityType, Object id) {
        CacheConfiguration config = resolver.resolve(entityType);
        if (!config.cacheable() || id == null) {
            return Mono.empty();
        }
        return invalidate(new CacheKey(config.region(), config.keyType(), id));
    }

    private Mono<Void> invalidate(CacheKey key) {
        Mono<Void> evict = provider.getCache(key.region()).evict(key);
        if (evictionBuffer != null) {
            evictionBuffer.recordKey(key);
        }
        // 엔티티 캐시 키 evict에 더해, 이 타입의 쿼리 결과도 통째 무효화(어떤 write든 결과 집합을 바꿀 수 있음).
        return evict.then(invalidateQueries(key.entityType()));
    }

    private Mono<Void> invalidateRegion(Class<?> entityType) {
        CacheConfiguration config = resolver.resolve(entityType);
        if (!config.cacheable()) {
            return Mono.empty();
        }
        Mono<Void> clear = provider.getCache(config.region()).clear();
        if (evictionBuffer != null) {
            evictionBuffer.recordRegionClear(config.region());
        }
        return clear.then(invalidateQueries(config.keyType()));
    }

    /**
     * 한 canonical 엔티티 타입의 쿼리 캐시 결과를 무효화한다. 쿼리 캐시 미배선이면 no-op. 트랜잭션 스코프에서는
     * 즉시 무효화하고 버퍼에 기록해 commit 후 재적용한다(엔티티 캐시와 동일한 정합성 패턴).
     */
    private Mono<Void> invalidateQueries(Class<?> canonicalType) {
        if (queryCache == null) {
            return Mono.empty();
        }
        Mono<Void> evict = queryCache.invalidate(canonicalType);
        if (evictionBuffer != null) {
            evictionBuffer.recordQueryInvalidate(canonicalType);
        }
        return evict;
    }

    /**
     * 대상 타입을 특정할 수 없는 native/compiled write 후 쿼리 캐시 전역 clear. 쿼리 캐시 미배선이면 no-op.
     */
    private Mono<Void> clearQueries() {
        if (queryCache == null) {
            return Mono.empty();
        }
        Mono<Void> clear = queryCache.clear();
        if (evictionBuffer != null) {
            evictionBuffer.recordQueryClearAll();
        }
        return clear;
    }

    @SuppressWarnings("unchecked")
    private static <T> Class<T> cast(Class<?> type) {
        return (Class<T>) type;
    }

    @SuppressWarnings("unchecked")
    private static <T> T castEntity(Object value) {
        return (T) value;
    }

    private static <E> List<E> toList(Iterable<E> iterable) {
        Objects.requireNonNull(iterable, "entities must not be null");
        if (iterable instanceof List<E> list) {
            return list;
        }
        List<E> collected = new ArrayList<>();
        iterable.forEach(collected::add);
        return collected;
    }
}
