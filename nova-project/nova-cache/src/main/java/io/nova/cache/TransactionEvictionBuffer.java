package io.nova.cache;

import io.nova.cache.spi.CacheKey;
import io.nova.cache.spi.ReactiveCacheProvider;
import io.nova.cache.spi.ReactiveQueryCache;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 트랜잭션 스코프 동안 발생한 캐시 무효화를 기록해 두었다가 <b>commit 이후</b> 다시 한번 적용하기 위한 버퍼.
 *
 * <p>Physical transaction invalidation is recorded, never eagerly applied: after commit,
 * {@link #flush(ReactiveCacheProvider, ReactiveQueryCache)} applies the recorded entity/query invalidation once.
 * Core writes completed during session flushing are marked by {@code PhysicalTransactionScope} immediately before
 * this replay. Rollback, error, and cancellation do not change shared caches. Legacy and arbitrary delegate writes
 * may record an explicit clear after successful delegate completion and replay it when their legacy scope succeeds.
 *
 * <p>엔티티 캐시(키/region)와 쿼리 캐시(타입/전역 clear)를 함께 기록해, 두 캐시 계층의 post-commit 재무효화를
 * 한 곳에서 순서대로 적용한다.
 *
 * <p>스레드 안전: 트랜잭션 파이프라인이 여러 스케줄러에서 접근할 수 있어 내부 접근을 동기화한다.
 */
final class TransactionEvictionBuffer {

    private final Set<CacheKey> keys = new LinkedHashSet<>();
    private final Set<String> regions = new LinkedHashSet<>();
    private final Set<Class<?>> queryTypes = new LinkedHashSet<>();
    private boolean providerClearAll;
    private boolean queryClearAll;
    private boolean flushed;
    private boolean physicalReplayRegistered;

    synchronized void recordKey(CacheKey key) {
        keys.add(key);
    }

    synchronized void recordRegionClear(String region) {
        regions.add(region);
    }

    /**
     * 한 엔티티 타입의 쿼리 캐시 무효화를 기록한다.
     */
    synchronized void recordQueryInvalidate(Class<?> entityType) {
        queryTypes.add(entityType);
    }

    /**
     * 쿼리 캐시 전역 clear를 기록한다(대상 불명 native/compiled write).
     */
    synchronized void recordQueryClearAll() {
        queryClearAll = true;
    }

    synchronized void recordProviderClearAll() {
        providerClearAll = true;
    }

    synchronized void markPhysicalReplayRegistered() {
        physicalReplayRegistered = true;
    }

    synchronized boolean hasPhysicalReplayRegistered() {
        return physicalReplayRegistered;
    }

    /**
     * 기록된 엔티티 캐시 region clear/key evict와 쿼리 캐시 무효화를 순서대로 재적용한다. commit 성공 후 호출한다.
     *
     * @param provider   엔티티 캐시 프로바이더
     * @param queryCache 쿼리 캐시(미배선이면 {@code null})
     */
    Mono<Void> flush(ReactiveCacheProvider provider, ReactiveQueryCache queryCache) {
        List<String> regionSnapshot;
        List<CacheKey> keySnapshot;
        List<Class<?>> queryTypeSnapshot;
        boolean providerClearAllSnapshot;
        boolean clearAllSnapshot;
        synchronized (this) {
            if (flushed) {
                return Mono.empty();
            }
            flushed = true;
            regionSnapshot = new ArrayList<>(regions);
            keySnapshot = new ArrayList<>(keys);
            queryTypeSnapshot = new ArrayList<>(queryTypes);
            providerClearAllSnapshot = providerClearAll;
            clearAllSnapshot = queryClearAll;
        }
        Mono<Void> entityEvict = providerClearAllSnapshot
                ? provider.clearAll()
                : Flux.fromIterable(regionSnapshot)
                        .concatMap(region -> provider.getCache(region).clear())
                        .then()
                        .thenMany(Flux.fromIterable(keySnapshot)
                                .concatMap(key -> provider.getCache(key.region()).evict(key)))
                        .then();
        Mono<Void> queryEvict;
        if (queryCache == null) {
            queryEvict = Mono.empty();
        } else if (clearAllSnapshot) {
            queryEvict = queryCache.clear();
        } else {
            queryEvict = Flux.fromIterable(queryTypeSnapshot)
                    .concatMap(queryCache::invalidate)
                    .then();
        }
        return entityEvict.then(queryEvict);
    }
}
