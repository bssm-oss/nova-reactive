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
 * <p>트랜잭션 안의 write는 즉시(eager) evict 되지만, commit 전 다른 트랜잭션이 옛 값을 DB에서 읽어 캐시를
 * 재-populate 할 수 있다. 이 버퍼는 그 창을 닫기 위해 commit 성공 후
 * {@link #flush(ReactiveCacheProvider, ReactiveQueryCache)}로 기록된 키/region(엔티티 캐시)과 쿼리 캐시 무효화를
 * 재적용한다. rollback 시에는 eager evict만 남으므로(엔트리 제거는 안전) flush를 생략해도 정합성이 유지된다.
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
    private boolean queryClearAll;

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
        boolean clearAllSnapshot;
        synchronized (this) {
            regionSnapshot = new ArrayList<>(regions);
            keySnapshot = new ArrayList<>(keys);
            queryTypeSnapshot = new ArrayList<>(queryTypes);
            clearAllSnapshot = queryClearAll;
        }
        Mono<Void> clearRegions = Flux.fromIterable(regionSnapshot)
                .concatMap(region -> provider.getCache(region).clear())
                .then();
        Mono<Void> evictKeys = Flux.fromIterable(keySnapshot)
                .concatMap(key -> provider.getCache(key.region()).evict(key))
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
        return clearRegions.then(evictKeys).then(queryEvict);
    }
}
