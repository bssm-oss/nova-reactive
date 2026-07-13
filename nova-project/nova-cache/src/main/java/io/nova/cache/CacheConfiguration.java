package io.nova.cache;

import io.nova.cache.annotation.CacheConcurrencyStrategy;

/**
 * 한 엔티티 타입에 대해 해석된 2차 캐시 설정.
 *
 * @param cacheable 캐시 대상 여부. {@code false}이면 {@link CachingReactiveEntityOperations}가 이 타입을
 *                  캐시 없이 그대로 delegate로 통과시킨다.
 * @param region    캐시 region 이름(cacheable일 때만 유효).
 * @param usage     동시성 전략(cacheable일 때만 유효).
 */
public record CacheConfiguration(boolean cacheable, String region, CacheConcurrencyStrategy usage) {

    /**
     * 캐시하지 않는 타입을 나타내는 설정.
     */
    public static CacheConfiguration notCacheable() {
        return new CacheConfiguration(false, "", CacheConcurrencyStrategy.READ_WRITE);
    }
}
