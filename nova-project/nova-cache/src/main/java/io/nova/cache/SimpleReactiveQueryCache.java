package io.nova.cache;

import io.nova.cache.spi.ReactiveQueryCache;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * in-process {@link ReactiveQueryCache} 기본 구현. 엔티티 <b>타입별로 분리된</b> 맵에 쿼리 키 → 결과 리스트를
 * 저장해, {@link #invalidate(Class)}가 한 타입의 모든 쿼리 결과를 한 번에 비운다.
 *
 * <p>모든 연산은 {@code Mono}로 감싸 blocking을 노출하지 않는다(AGENTS.md rule #4). 내부는 순수 in-memory
 * 맵 조작뿐이며 I/O가 없다. 선택적 TTL/타입당 최대 크기를 {@link CacheOptions}로 지원한다(만료는 get 시 지연
 * 제거, 초과 시 LRU 제거).
 *
 * <p>저장 값은 조회 결과 엔티티 리스트를 방어적 복사한 <b>불변 스냅샷</b>이다 — 발행 후 호출자가 리스트를
 * 변형해도 캐시가 오염되지 않는다. (엔티티 <i>인스턴스</i> 자체는 엔티티 캐시와 동일하게 공유되며, 이는 기존
 * 2차 캐시 설계와 일관된 aliasing 특성이다.)
 *
 * <p>스레드 안전: 타입별 하위 맵은 자체 monitor로 감싸 접근한다.
 */
public final class SimpleReactiveQueryCache implements ReactiveQueryCache {

    private final CacheOptions options;
    private final ConcurrentHashMap<Class<?>, TypeRegion> regions = new ConcurrentHashMap<>();

    /**
     * 만료 없음 + 무제한 크기.
     */
    public SimpleReactiveQueryCache() {
        this(CacheOptions.unbounded());
    }

    /**
     * 공통 옵션(TTL/타입당 최대 엔트리 수)을 지정한다.
     */
    public SimpleReactiveQueryCache(CacheOptions options) {
        this.options = Objects.requireNonNull(options, "options must not be null");
    }

    @Override
    public Mono<List<Object>> get(Class<?> entityType, String queryKey) {
        Objects.requireNonNull(entityType, "entityType must not be null");
        Objects.requireNonNull(queryKey, "queryKey must not be null");
        return Mono.defer(() -> {
            TypeRegion region = regions.get(entityType);
            if (region == null) {
                return Mono.empty();
            }
            List<Object> hit = region.get(queryKey);
            return hit == null ? Mono.empty() : Mono.just(hit);
        });
    }

    @Override
    public Mono<Void> put(Class<?> entityType, String queryKey, List<Object> results) {
        Objects.requireNonNull(entityType, "entityType must not be null");
        Objects.requireNonNull(queryKey, "queryKey must not be null");
        Objects.requireNonNull(results, "results must not be null");
        // 방어적 불변 스냅샷 — 호출자의 이후 변형으로부터 캐시 격리.
        List<Object> snapshot = List.copyOf(results);
        return Mono.fromRunnable(() ->
                regions.computeIfAbsent(entityType, t -> new TypeRegion(options)).put(queryKey, snapshot));
    }

    @Override
    public Mono<Void> invalidate(Class<?> entityType) {
        Objects.requireNonNull(entityType, "entityType must not be null");
        return Mono.fromRunnable(() -> regions.remove(entityType));
    }

    @Override
    public Mono<Void> clear() {
        return Mono.fromRunnable(regions::clear);
    }

    /**
     * 통계/테스트용: 저장된 엔티티 타입 수(근사값, 만료 미반영).
     */
    long regionCount() {
        return regions.size();
    }

    /**
     * 통계/테스트용: 한 타입에 캐시된 쿼리 엔트리 수(만료되지 않은 것 근사값).
     */
    long size(Class<?> entityType) {
        TypeRegion region = regions.get(entityType);
        return region == null ? 0L : region.size();
    }

    /**
     * 한 엔티티 타입의 쿼리 키 → 결과 스냅샷 저장소. LRU + TTL을 {@link SimpleReactiveCache}와 동일한 방식으로 지원.
     */
    private static final class TypeRegion {
        private final CacheOptions options;
        private final Map<String, Entry> store;

        TypeRegion(CacheOptions options) {
            this.options = options;
            this.store = new java.util.LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Entry> eldest) {
                    return options.isBounded() && size() > options.maximumSize();
                }
            };
        }

        List<Object> get(String key) {
            synchronized (store) {
                Entry entry = store.get(key);
                if (entry == null) {
                    return null;
                }
                if (entry.isExpired()) {
                    store.remove(key);
                    return null;
                }
                return entry.value();
            }
        }

        void put(String key, List<Object> value) {
            synchronized (store) {
                store.put(key, new Entry(value, expireAtNanos()));
            }
        }

        long size() {
            synchronized (store) {
                return store.size();
            }
        }

        private long expireAtNanos() {
            if (!options.hasTtl()) {
                return 0L;
            }
            return System.nanoTime() + options.timeToLive().toNanos();
        }
    }

    private record Entry(List<Object> value, long expireAtNanos) {
        boolean isExpired() {
            return expireAtNanos != 0L && System.nanoTime() - expireAtNanos >= 0;
        }
    }
}
