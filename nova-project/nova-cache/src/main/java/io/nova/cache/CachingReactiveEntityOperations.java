package io.nova.cache;

import io.nova.cache.spi.CacheKey;
import io.nova.cache.spi.ReactiveCache;
import io.nova.cache.spi.ReactiveCacheProvider;
import io.nova.core.ReactiveEntityOperations;
import io.nova.core.RowAccessor;
import io.nova.fetch.FetchGroup;
import io.nova.metadata.EntityMetadataFactory;
import io.nova.query.AggregateRow;
import io.nova.query.AggregateSpec;
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
 *       broadcast)은 v2(외부 캐시 프로바이더)에서 다룬다. {@code findById(..., FetchGroup)}와 임의 쿼리
 *       결과는 v1에서 캐시하지 않는다(자식 hydration 편차 회피).</li>
 * </ul>
 *
 * <p>{@code @Cacheable}이 아닌 타입은 캐시 없이 그대로 delegate로 통과한다 — 기존 리액티브 동작과 동일하다.
 */
public final class CachingReactiveEntityOperations implements ReactiveEntityOperations {

    private final ReactiveEntityOperations delegate;
    private final EntityMetadataFactory metadataFactory;
    private final CacheConfigurationResolver resolver;
    private final ReactiveCacheProvider provider;
    /** 읽기 결과를 캐시에 채울지 여부. 트랜잭션 스코프 내부에서는 {@code false}(미커밋 유출 방지). */
    private final boolean populateOnRead;
    /** 트랜잭션 스코프에서만 non-null. write 무효화를 기록해 commit 후 재적용한다. */
    private final TransactionEvictionBuffer evictionBuffer;

    public CachingReactiveEntityOperations(
            ReactiveEntityOperations delegate,
            EntityMetadataFactory metadataFactory,
            CacheConfigurationResolver resolver,
            ReactiveCacheProvider provider) {
        this(delegate, metadataFactory, resolver, provider, true, null);
    }

    private CachingReactiveEntityOperations(
            ReactiveEntityOperations delegate,
            EntityMetadataFactory metadataFactory,
            CacheConfigurationResolver resolver,
            ReactiveCacheProvider provider,
            boolean populateOnRead,
            TransactionEvictionBuffer evictionBuffer) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.metadataFactory = Objects.requireNonNull(metadataFactory, "metadataFactory must not be null");
        this.resolver = Objects.requireNonNull(resolver, "resolver must not be null");
        this.provider = Objects.requireNonNull(provider, "provider must not be null");
        this.populateOnRead = populateOnRead;
        this.evictionBuffer = evictionBuffer;
    }

    private CachingReactiveEntityOperations withDelegate(
            ReactiveEntityOperations inner, boolean populate, TransactionEvictionBuffer buffer) {
        return new CachingReactiveEntityOperations(inner, metadataFactory, resolver, provider, populate, buffer);
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
        Flux<T> result = delegate.findAll(entityType, querySpec);
        if (!config.cacheable() || !populateOnRead) {
            return result;
        }
        // 조회된 엔티티를 캐시에 채워 이후 findById가 히트하도록 한다(임의 쿼리 자체는 캐시하지 않는다).
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
        return delegate.executeNative(query).flatMap(count -> provider.clearAll().thenReturn(count));
    }

    @Override
    public Mono<Long> execute(CompiledQuery query, Object... bindings) {
        return delegate.execute(query, bindings).flatMap(count -> provider.clearAll().thenReturn(count));
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

    // --- transaction / read session scoping --------------------------------

    @Override
    public <R> Mono<R> inTransaction(Function<ReactiveEntityOperations, Mono<R>> callback) {
        TransactionEvictionBuffer buffer = new TransactionEvictionBuffer();
        Mono<R> body = delegate.inTransaction(inner -> callback.apply(withDelegate(inner, false, buffer)));
        // commit 성공 후 무효화 재적용(post-commit re-evict). 값이 없거나 있어도 반드시 한 번 flush.
        return body.flatMap(result -> buffer.flush(provider).thenReturn(result))
                .switchIfEmpty(Mono.defer(() -> buffer.flush(provider).then(Mono.empty())));
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
        return evict;
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
        return clear;
    }

    @SuppressWarnings("unchecked")
    private static <T> Class<T> cast(Class<?> type) {
        return (Class<T>) type;
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
