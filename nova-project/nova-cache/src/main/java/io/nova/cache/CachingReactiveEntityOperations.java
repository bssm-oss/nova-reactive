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
import io.nova.tx.PhysicalTransactionScope;
import io.nova.tx.TransactionWriteObservation;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.context.ContextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * {@link ReactiveEntityOperations}를 감싸 2차 캐시(read-through + write invalidation)를 더하는 데코레이터.
 * 캐시 자체는 operations 바깥에 배선하고, core의 physical-transaction write marker를 사용해 자동 session
 * flush를 포함한 실제 SQL write 완료 여부를 관찰한다.
 *
 * <h2>동작</h2>
 * <ul>
 *   <li><b>read-through</b>: {@code findById(Class, id)}가 캐시 히트면 DB를 우회하고, 미스면 delegate로
 *       조회한 뒤 {@code @Cacheable} 엔티티를 캐시에 채운다.</li>
 *   <li><b>write invalidation</b>: {@code save}/{@code update}/{@code delete}(및 batch/변형)는 delegate 실행
 *       후 모든 엔티티 region과 쿼리 캐시를 clear 한다. eager hydration된 그래프가 다른 타입의 상태도
 *       품을 수 있으므로, cacheable 여부와 대상 행 특정 가능 여부에 관계없이 전역 무효화한다. Physical
 *       transaction writes record the clear for after-commit only.</li>
 *   <li><b>query cache read-through(opt-in)</b>: {@link ReactiveQueryCache}를 주입하면
 *       {@code findAll(Class, QuerySpec)} 결과를 정규화된 스펙 키로 캐시한다(히트 시 0 SQL). Every successful
 *       write globally invalidates query results because eager graphs can include associated entity types. 쿼리
 *       캐시가 없으면(기본) 이 경로는 기존과 동일하게 delegate로 통과하고 조회 엔티티로 엔티티 캐시만 warming 한다.</li>
 * </ul>
 *
 * <h2>정합성 계약 (v1)</h2>
 * <ul>
 *   <li>캐시는 <b>읽기에서만 채워지고 쓰기에서는 무효화만</b> 된다 — 미커밋 write 값을 캐시에 넣지 않는다.
 *       물리 트랜잭션의 rollback, error, cancellation, commit failure는 shared cache를 변경하지 않는다.</li>
 *   <li>물리 트랜잭션 안의 모든 엔티티 read는 Reactor Context를 확인해 cache hit을 우회하지만 shared caches를
 *       변경하지 않는다. Successful writes record one global clear, which runs after physical commit and is
 *       shared by nested participants.</li>
 *   <li>알려진 한계: 단일 JVM in-process 캐시로, 동시 writer 간 완전한 트랜잭셔널 정합성(외부 post-commit
 *       broadcast)은 외부 캐시 프로바이더에서 다룬다. {@code findById(..., FetchGroup)}, projection/paged/slice
 *       조회, count/exists 스칼라, native/compiled 조회 결과는 캐시하지 않는다(자식 hydration 편차·범위 위험
 *       회피). 쿼리 캐시는 {@code findAll(Class, QuerySpec)} 엔티티 결과에만 적용되며, {@link ReactiveQueryCache}를
 *       주입한 경우에만(opt-in) 활성화된다.</li>
 *   <li>캐시에서 발행되는 엔티티와 쿼리 결과는 매핑-aware detached graph 복사본이다. 한 hit 안에서는 공유
 *       참조와 cycle을 보존하지만, 서로 다른 hit 사이에는 인스턴스를 공유하지 않는다.</li>
 *   <li><b>배선 경계(EntityManager 결합):</b> {@code ReactiveEntityManager}는 반드시 이 캐시 데코레이터
 *       <b>위에</b> 얹어라(예: {@code new SimpleReactiveEntityManager(NovaCache.caching(base, ...), mf)}).
 *       그래야 EM의 persist/merge/remove가 이 데코레이터의 write invalidation 경로를 거친다. EM을 캐시되지
 *       않은 <b>base operations 위에</b> 만들고 <em>별도의</em> 캐시 데코레이터로 읽으면, EM write가 무효화를
 *       우회해 그 캐시가 stale 값을 낼 수 있다. 명시적 또는 commit 직전 자동 {@link #flush()}가 write SQL을
 *       성공적으로 완료하면 physical scope가 이를 기록하고, outer commit 성공 뒤 전역 캐시를 한 번 비운다.
 *       캐시와 EM은 같은 데코레이터 스택으로 결합해야 이 after-commit 경계를 공유한다.</li>
 * </ul>
 *
 * <p>{@code @Cacheable}이 아닌 타입의 read는 캐시 없이 delegate로 통과한다. Write는 eager graph에 포함된
 * cacheable 연관 타입이 stale해지지 않도록 cacheable 여부와 무관하게 전역 무효화에 참여한다.
 */
public final class CachingReactiveEntityOperations implements ReactiveEntityOperations {

    private final ReactiveEntityOperations delegate;
    private final EntityMetadataFactory metadataFactory;
    private final MappingAwareEntityGraphCopier graphCopier;
    private final CacheConfigurationResolver resolver;
    private final ReactiveCacheProvider provider;
    /** 쿼리 결과 캐시(opt-in). {@code null}이면 쿼리 캐싱 비활성 — {@code findAll(Class, QuerySpec)}은 기존 통과. */
    private final ReactiveQueryCache queryCache;
    /** 읽기 결과를 캐시에 채울지 여부. 트랜잭션 스코프 내부에서는 {@code false}(미커밋 유출 방지). */
    private final boolean populateOnRead;
    /** 트랜잭션 스코프에서만 non-null. write 무효화를 기록해 commit 후 재적용한다. */
    private final TransactionEvictionBuffer evictionBuffer;
    private final Object transactionEvictionResourceKey;

    public CachingReactiveEntityOperations(
            ReactiveEntityOperations delegate,
            EntityMetadataFactory metadataFactory,
            CacheConfigurationResolver resolver,
            ReactiveCacheProvider provider) {
        this(delegate, metadataFactory, resolver, provider, null, true, null, new Object());
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
        this(delegate, metadataFactory, resolver, provider, queryCache, true, null, new Object());
    }

    private CachingReactiveEntityOperations(
            ReactiveEntityOperations delegate,
            EntityMetadataFactory metadataFactory,
            CacheConfigurationResolver resolver,
            ReactiveCacheProvider provider,
            ReactiveQueryCache queryCache,
            boolean populateOnRead,
            TransactionEvictionBuffer evictionBuffer,
            Object transactionEvictionResourceKey) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.metadataFactory = Objects.requireNonNull(metadataFactory, "metadataFactory must not be null");
        this.graphCopier = new MappingAwareEntityGraphCopier(this.metadataFactory);
        this.resolver = Objects.requireNonNull(resolver, "resolver must not be null");
        this.provider = Objects.requireNonNull(provider, "provider must not be null");
        this.queryCache = queryCache; // nullable — opt-in
        this.populateOnRead = populateOnRead;
        this.evictionBuffer = evictionBuffer;
        this.transactionEvictionResourceKey = transactionEvictionResourceKey;
    }

    private CachingReactiveEntityOperations withDelegate(
            ReactiveEntityOperations inner, boolean populate, TransactionEvictionBuffer buffer) {
        return new CachingReactiveEntityOperations(
                inner, metadataFactory, resolver, provider, queryCache, populate, buffer, transactionEvictionResourceKey);
    }

    // --- read-through ------------------------------------------------------

    @Override
    @SuppressWarnings("unchecked")
    public <T, ID> Mono<T> findById(Class<T> entityType, ID id) {
        return Mono.deferContextual(context -> {
            if (hasActivePhysicalScope(context)) {
                return delegate.findById(entityType, id);
            }
            CacheConfiguration config = resolver.resolve(entityType);
            if (!config.cacheable() || id == null || !populateOnRead) {
                return delegate.findById(entityType, id);
            }
            CacheKey key = new CacheKey(config.region(), config.keyType(), id);
            ReactiveCache cache = provider.getCache(config.region());
            Mono<T> load = delegate.findById(entityType, id)
                    .flatMap(loaded -> cache.put(key, graphCopier.copy(loaded))
                            .thenReturn(graphCopier.copy(loaded)));
            return cache.get(key).map(value -> graphCopier.<T>copy(castEntity(value))).switchIfEmpty(load);
        });
    }

    @Override
    public <T, ID> Mono<Boolean> existsById(Class<T> entityType, ID id) {
        return Mono.deferContextual(context -> {
            if (hasActivePhysicalScope(context)) {
                return delegate.existsById(entityType, id);
            }
            CacheConfiguration config = resolver.resolve(entityType);
            if (!config.cacheable() || id == null || !populateOnRead) {
                return delegate.existsById(entityType, id);
            }
            CacheKey key = new CacheKey(config.region(), config.keyType(), id);
            return provider.getCache(config.region()).get(key)
                    .map(value -> Boolean.TRUE)
                    .switchIfEmpty(delegate.existsById(entityType, id));
        });
    }

    @Override
    public <T, ID> Flux<T> findAllById(Class<T> entityType, Iterable<ID> ids) {
        return Flux.deferContextual(context -> hasActivePhysicalScope(context)
                ? delegate.findAllById(entityType, ids)
                : findAllByIdOutsideTransaction(entityType, ids));
    }

    private <T, ID> Flux<T> findAllByIdOutsideTransaction(Class<T> entityType, Iterable<ID> ids) {
        CacheConfiguration config = resolver.resolve(entityType);
        Flux<T> result = delegate.findAllById(entityType, ids);
        if (!config.cacheable() || !populateOnRead) {
            return result;
        }
        return result.concatMap(entity -> putEntity(entity).thenReturn(entity));
    }

    @Override
    public <T> Flux<T> findAll(Class<T> entityType, QuerySpec querySpec) {
        return Flux.deferContextual(context -> hasActivePhysicalScope(context)
                ? delegate.findAll(entityType, querySpec)
                : findAllOutsideTransaction(entityType, querySpec));
    }

    private <T> Flux<T> findAllOutsideTransaction(Class<T> entityType, QuerySpec querySpec) {
        CacheConfiguration config = resolver.resolve(entityType);
        boolean warmEntityCache = config.cacheable() && populateOnRead;
        // 쿼리 캐시 read-through: opt-in(queryCache != null) + cacheable + 트랜잭션 밖(populateOnRead)
        // + 잠금 없는 쿼리만. 잠금(FOR UPDATE/SHARE)은 항상 DB를 쳐야 하므로 캐시하지 않는다.
        if (queryCache != null && warmEntityCache && querySpec.lockMode() == LockMode.NONE) {
            // 무효화 파티션은 canonical keyType(subtype write가 base-type query까지 통째 무효화 — over-invalidation,
            // 안전). 그러나 캐시 키 문자열은 <b>실제 쿼리 타입(entityType)</b>으로 만든다. 상속 계층에서
            // findAll(Base, spec)과 findAll(Sub, spec)은 delegate가 서로 다른 결과셋(전체 vs isInstance 부분집합)을
            // 내므로, 둘이 같은 canonical keyType으로 키를 공유하면 교차 서빙되어 잘못된 결과/ClassCastException을
            // 낸다. QuerySpecCacheKey가 타입명을 키 선두에 넣으므로 root/subtype 키가 확실히 구분된다.
            Class<?> partition = config.keyType();
            String key = QuerySpecCacheKey.of(entityType, querySpec);
            Flux<T> onMiss = Flux.defer(() -> delegate.findAll(entityType, querySpec)
                    .collectList()
                    .flatMapMany(list -> {
                        List<Object> snapshot = graphCopier.copyAll(list);
                        return queryCache.put(partition, key, snapshot)
                                // 결과를 쿼리 캐시에 저장 + 엔티티 캐시도 warming(이후 findById 히트).
                                .thenMany(Flux.fromIterable(graphCopier.copyAll(snapshot)))
                                .concatMap(entity -> putEntity(entity).thenReturn(entity))
                                .map(CachingReactiveEntityOperations::<T>castEntity);
                    }));
            // 빈 리스트도 히트로 취급해야 하므로 Mono 존재 여부로 hit/miss를 판별한다(빈 Flux를
            // switchIfEmpty로 miss 처리하면 빈 결과가 매번 재실행됨).
            return queryCache.get(partition, key)
                    .map(Optional::of)
                    .defaultIfEmpty(Optional.empty())
                    .flatMapMany(hit -> hit.isPresent()
                            ? Flux.fromIterable(graphCopier.copyAll(hit.get())).map(CachingReactiveEntityOperations::castEntity)
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
    public <T, ID> Mono<T> findById(Class<T> entityType, ID id, io.nova.graph.EntityGraph<T> entityGraph) {
        // EntityGraph(중첩 subgraph 포함) 경로는 delegate가 depth>1 hydration을 담당하므로 그대로 위임한다
        // (interface default 로 fallback 하면 flat FetchGroup 으로만 풀려 중첩 fetch 가 유실됨).
        return delegate.findById(entityType, id, entityGraph);
    }

    @Override
    public <T> Flux<T> findAll(Class<T> entityType, io.nova.graph.EntityGraph<T> entityGraph) {
        return delegate.findAll(entityType, entityGraph);
    }

    @Override
    public <T> Flux<T> findAll(Class<T> entityType, CompiledQuery query, Object... bindings) {
        return delegate.findAll(entityType, query, bindings);
    }

    // --- write invalidation -------------------------------------------------

    @Override
    public <T> Mono<T> save(T entity) {
        return delegate.save(entity).flatMap(saved -> clearAllCaches().thenReturn(saved));
    }

    @Override
    public <T> Mono<T> update(T entity, Iterable<String> fields) {
        return delegate.update(entity, fields).flatMap(updated -> clearAllCaches().thenReturn(updated));
    }

    @Override
    public <T> Flux<T> saveAll(Iterable<T> entities) {
        List<T> list = toList(entities);
        return delegate.saveAll(list).concatMap(saved -> clearAllCaches().thenReturn(saved));
    }

    @Override
    public <T> Mono<Long> delete(T entity) {
        return delegate.delete(entity).flatMap(count -> clearAllCaches().thenReturn(count));
    }

    @Override
    public <T, ID> Mono<Long> deleteById(Class<T> entityType, ID id) {
        return delegate.deleteById(entityType, id).flatMap(count -> clearAllCaches().thenReturn(count));
    }

    @Override
    public <T> Mono<Long> deleteAll(Iterable<T> entities) {
        List<T> list = toList(entities);
        return delegate.deleteAll(list)
                .flatMap(count -> clearAllCaches().thenReturn(count));
    }

    @Override
    public <T, ID> Mono<Long> deleteAllById(Class<T> entityType, Iterable<ID> ids) {
        List<ID> list = toList(ids);
        return delegate.deleteAllById(entityType, list)
                .flatMap(count -> clearAllCaches().thenReturn(count));
    }

    @Override
    public <T> Mono<Long> deleteAll(Class<T> entityType, QuerySpec querySpec) {
        // predicate로 지운 행을 특정할 수 없어 해당 타입 region을 통째로 비운다(보수적).
        return delegate.deleteAll(entityType, querySpec)
                .flatMap(count -> clearAllCaches().thenReturn(count));
    }

    @Override
    public <T> Mono<Long> update(Class<T> entityType, Updater<T> updater) {
        // bulk update도 대상 행을 특정할 수 없어 region을 통째로 비운다.
        return delegate.update(entityType, updater)
                .flatMap(count -> clearAllCaches().thenReturn(count));
    }

    // --- native / compiled: 대상 불명 → 보수적 전역 무효화 ----------------------

    @Override
    public Mono<Long> executeNative(NativeQuery query) {
        return delegate.executeNative(query)
                .flatMap(count -> clearAllCaches().thenReturn(count));
    }

    @Override
    public Mono<Long> execute(CompiledQuery query, Object... bindings) {
        return delegate.execute(query, bindings)
                .flatMap(count -> clearAllCaches().thenReturn(count));
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
     * 보존한다. flush가 write SQL을 성공적으로 완료하면 core가 physical scope를 표시하며, shared cache는
     * 즉시 변경되지 않고 outer transaction commit 성공 뒤 한 번만 전역 무효화된다.
     */
    @Override
    public Mono<Void> flush() {
        return delegate.flush();
    }

    // --- transaction / read session scoping --------------------------------

    @Override
    public <R> Mono<R> inTransaction(Function<ReactiveEntityOperations, Mono<R>> callback) {
        return Mono.deferContextual(context -> {
            boolean participating = hasActivePhysicalScope(context);
            TransactionEvictionBuffer localBuffer = new TransactionEvictionBuffer();
            TransactionWriteObservation observation = new TransactionWriteObservation();
            Mono<R> body = delegate.inTransaction(inner -> Mono.deferContextual(transactionContext ->
                    callback.apply(withDelegate(inner, false, transactionBuffer(transactionContext, localBuffer)))))
                    .contextWrite(transactionContext ->
                            transactionContext.put(TransactionWriteObservation.CONTEXT_KEY, observation));
            if (participating) {
                return body;
            }
            return body.flatMap(result -> flushLegacyBuffer(localBuffer, observation).thenReturn(result))
                    .switchIfEmpty(Mono.defer(() -> flushLegacyBuffer(localBuffer, observation).then(Mono.empty())));
        });
    }

    @Override
    public <R> Mono<R> inReadSession(Function<ReactiveEntityOperations, Mono<R>> callback) {
        return delegate.inReadSession(inner ->
                callback.apply(withDelegate(inner, populateOnRead, evictionBuffer)));
    }

    // --- invalidation helpers ----------------------------------------------

    private TransactionEvictionBuffer transactionBuffer(ContextView context, TransactionEvictionBuffer fallback) {
        if (!hasActivePhysicalScope(context)) {
            return fallback;
        }
        PhysicalTransactionScope scope = context.get(PhysicalTransactionScope.CONTEXT_KEY);
        return scope.getOrCreateResource(transactionEvictionResourceKey, () -> {
            fallback.markPhysicalReplayRegistered();
            scope.afterCommit(() -> {
                // Core-managed writes can complete during before-commit session flushing, after the decorator's
                // explicit write method returned. Mark their global invalidation immediately before replay.
                if (scope.hasCompletedWrite()) {
                    fallback.recordProviderClearAll();
                    fallback.recordQueryClearAll();
                }
                return fallback.flush(provider, queryCache);
            });
            return fallback;
        });
    }

    private Mono<Void> flushLegacyBuffer(
            TransactionEvictionBuffer buffer, TransactionWriteObservation observation) {
        if (buffer.hasPhysicalReplayRegistered() || !observation.hasCompletedWrite()) {
            return Mono.empty();
        }
        buffer.recordProviderClearAll();
        buffer.recordQueryClearAll();
        return buffer.flush(provider, queryCache);
    }

    private static boolean hasActivePhysicalScope(ContextView context) {
        return context.hasKey(PhysicalTransactionScope.CONTEXT_KEY)
                && context.<PhysicalTransactionScope>get(PhysicalTransactionScope.CONTEXT_KEY).isActive();
    }

    /** Records a physical-transaction clear for after-commit only. */
    private Mono<Void> clearTransactionalCaches(ContextView context) {
        TransactionEvictionBuffer buffer = transactionBuffer(context,
                evictionBuffer != null ? evictionBuffer : new TransactionEvictionBuffer());
        buffer.recordProviderClearAll();
        buffer.recordQueryClearAll();
        return Mono.empty();
    }

    private Mono<Void> clearAllCaches() {
        return Mono.deferContextual(context -> {
            if (hasActivePhysicalScope(context)) {
                return clearTransactionalCaches(context);
            }
            TransactionEvictionBuffer buffer = activeTransactionBuffer(context);
            if (hasWriteObservation(context)) {
                context.<TransactionWriteObservation>get(TransactionWriteObservation.CONTEXT_KEY)
                        .markWriteCompleted();
                if (buffer != null) {
                    buffer.recordProviderClearAll();
                    buffer.recordQueryClearAll();
                }
                return Mono.empty();
            }
            if (buffer != null) {
                buffer.recordProviderClearAll();
            }
            return provider.clearAll().then(clearQueries(buffer));
        });
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
        return provider.getCache(config.region()).put(
                new CacheKey(config.region(), config.keyType(), id), graphCopier.copy(entity));
    }

    /** 성공한 ORM write 후 쿼리 캐시 전역 clear. 쿼리 캐시 미배선이면 no-op. */
    private Mono<Void> clearQueries() {
        return Mono.deferContextual(context -> clearQueries(activeTransactionBuffer(context)));
    }

    private Mono<Void> clearQueries(TransactionEvictionBuffer buffer) {
        if (queryCache == null) {
            return Mono.empty();
        }
        Mono<Void> clear = queryCache.clear();
        if (buffer != null) {
            buffer.recordQueryClearAll();
        }
        return clear;
    }

    private TransactionEvictionBuffer activeTransactionBuffer(ContextView context) {
        if (hasActivePhysicalScope(context)) {
            return transactionBuffer(context, evictionBuffer != null ? evictionBuffer : new TransactionEvictionBuffer());
        }
        return evictionBuffer;
    }

    private static boolean hasWriteObservation(ContextView context) {
        return context.hasKey(TransactionWriteObservation.CONTEXT_KEY);
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
