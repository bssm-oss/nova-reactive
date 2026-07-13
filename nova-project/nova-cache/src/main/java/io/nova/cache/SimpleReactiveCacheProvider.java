package io.nova.cache;

import io.nova.cache.spi.ReactiveCache;
import io.nova.cache.spi.ReactiveCacheProvider;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * in-process {@link ReactiveCacheProvider} 기본 구현. region 이름별로 {@link SimpleReactiveCache}를
 * lazy 하게 생성해 재사용한다. 모든 region은 생성 시점의 공통 {@link CacheOptions}(TTL/최대 크기)를 공유한다.
 *
 * <p>외부 캐시(Redis 등)는 {@link ReactiveCacheProvider}를 직접 구현해 {@link NovaCache}에 주입하면 되며,
 * 이 클래스는 v1의 분산 없는(단일 JVM) 기본값이다.
 */
public final class SimpleReactiveCacheProvider implements ReactiveCacheProvider {

    private final CacheOptions options;
    private final ConcurrentHashMap<String, ReactiveCache> caches = new ConcurrentHashMap<>();

    /**
     * 만료 없음 + 무제한 크기의 프로바이더.
     */
    public SimpleReactiveCacheProvider() {
        this(CacheOptions.unbounded());
    }

    /**
     * 모든 region에 적용할 공통 옵션을 지정한다.
     */
    public SimpleReactiveCacheProvider(CacheOptions options) {
        this.options = Objects.requireNonNull(options, "options must not be null");
    }

    @Override
    public ReactiveCache getCache(String region) {
        Objects.requireNonNull(region, "region must not be null");
        return caches.computeIfAbsent(region, name -> new SimpleReactiveCache(name, options));
    }

    @Override
    public Mono<Void> clearAll() {
        return Flux.fromIterable(caches.values())
                .concatMap(ReactiveCache::clear)
                .then();
    }
}
