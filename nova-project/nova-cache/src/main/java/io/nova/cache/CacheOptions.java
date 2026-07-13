package io.nova.cache;

import java.time.Duration;
import java.util.Objects;

/**
 * {@link SimpleReactiveCacheProvider}가 생성하는 in-process region의 동작 옵션.
 *
 * @param timeToLive  엔트리 만료 시간. {@code null}이면 만료 없음(명시적 evict 전까지 유지).
 * @param maximumSize region당 최대 엔트리 수. {@code 0} 이하이면 무제한. 초과 시 LRU(접근 순서) 기준으로
 *                    가장 오래 사용되지 않은 엔트리를 제거한다.
 */
public record CacheOptions(Duration timeToLive, long maximumSize) {

    public CacheOptions {
        if (timeToLive != null && (timeToLive.isNegative() || timeToLive.isZero())) {
            throw new IllegalArgumentException("timeToLive must be positive when set, was " + timeToLive);
        }
    }

    /**
     * 만료 없음 + 무제한 크기. 통합 테스트/소규모 캐시의 기본값.
     */
    public static CacheOptions unbounded() {
        return new CacheOptions(null, 0);
    }

    /**
     * TTL만 설정하고 크기는 무제한.
     */
    public static CacheOptions ttl(Duration timeToLive) {
        return new CacheOptions(Objects.requireNonNull(timeToLive, "timeToLive"), 0);
    }

    /**
     * 최대 크기만 설정하고 만료는 없음.
     */
    public static CacheOptions maxSize(long maximumSize) {
        return new CacheOptions(null, maximumSize);
    }

    boolean hasTtl() {
        return timeToLive != null;
    }

    boolean isBounded() {
        return maximumSize > 0;
    }
}
