package io.nova.cache;

import io.nova.cache.spi.CacheKey;
import io.nova.cache.spi.ReactiveCache;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * in-process {@link ReactiveCache} 기본 구현. LRU(접근 순서) {@link LinkedHashMap}을 자체 monitor로 감싸
 * 스레드 안전하게 접근하며, 선택적 TTL/최대 크기를 지원한다.
 *
 * <p>모든 연산은 {@code Mono}로 감싸 blocking을 노출하지 않는다(AGENTS.md rule #4). 내부 임계 구역은
 * 순수 in-memory 맵 조작뿐이며 I/O가 없다 — DB 호출 같은 blocking과 무관하다.
 *
 * <p>TTL은 {@link System#nanoTime()} 기준 monotonic 만료로 wall-clock 조정에 영향받지 않는다. 만료된
 * 엔트리는 {@link #get(CacheKey)} 시 지연 제거된다.
 */
public final class SimpleReactiveCache implements ReactiveCache {

    private final String region;
    private final CacheOptions options;
    private final Map<CacheKey, Entry> store;

    public SimpleReactiveCache(String region, CacheOptions options) {
        this.region = Objects.requireNonNull(region, "region must not be null");
        this.options = Objects.requireNonNull(options, "options must not be null");
        // accessOrder=true 로 LRU. removeEldestEntry로 최대 크기 초과 시 가장 오래 사용되지 않은 엔트리 제거.
        this.store = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<CacheKey, Entry> eldest) {
                return options.isBounded() && size() > options.maximumSize();
            }
        };
    }

    @Override
    public String region() {
        return region;
    }

    @Override
    public Mono<Object> get(CacheKey key) {
        Objects.requireNonNull(key, "key must not be null");
        return Mono.defer(() -> {
            synchronized (store) {
                Entry entry = store.get(key);
                if (entry == null) {
                    return Mono.empty();
                }
                if (entry.isExpired()) {
                    store.remove(key);
                    return Mono.empty();
                }
                return Mono.just(entry.value());
            }
        });
    }

    @Override
    public Mono<Void> put(CacheKey key, Object value) {
        Objects.requireNonNull(key, "key must not be null");
        if (value == null) {
            // 캐시는 부재(null)를 저장하지 않는다 — negative caching은 v1 범위 밖.
            return Mono.empty();
        }
        return Mono.fromRunnable(() -> {
            synchronized (store) {
                store.put(key, new Entry(value, expireAtNanos()));
            }
        });
    }

    @Override
    public Mono<Void> evict(CacheKey key) {
        Objects.requireNonNull(key, "key must not be null");
        return Mono.fromRunnable(() -> {
            synchronized (store) {
                store.remove(key);
            }
        });
    }

    @Override
    public Mono<Void> clear() {
        return Mono.fromRunnable(() -> {
            synchronized (store) {
                store.clear();
            }
        });
    }

    @Override
    public long size() {
        synchronized (store) {
            return store.size();
        }
    }

    private long expireAtNanos() {
        if (!options.hasTtl()) {
            return 0L; // 만료 없음 sentinel.
        }
        return System.nanoTime() + options.timeToLive().toNanos();
    }

    private record Entry(Object value, long expireAtNanos) {
        boolean isExpired() {
            return expireAtNanos != 0L && System.nanoTime() - expireAtNanos >= 0;
        }
    }
}
