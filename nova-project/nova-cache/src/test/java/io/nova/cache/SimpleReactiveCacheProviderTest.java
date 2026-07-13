package io.nova.cache;

import io.nova.cache.spi.CacheKey;
import io.nova.cache.spi.ReactiveCache;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * {@link SimpleReactiveCacheProvider}/{@link SimpleReactiveCache}의 get/put/evict, region 분리, TTL 만료,
 * 최대 크기 LRU 제거를 검증한다. 모든 캐시 연산이 {@code Mono}로 발행되는지 {@link StepVerifier}로 확인한다.
 */
class SimpleReactiveCacheProviderTest {

    private static CacheKey key(String region, Object id) {
        return new CacheKey(region, String.class, id);
    }

    @Test
    void putThenGetReturnsValueAndMissIsEmpty() {
        ReactiveCache cache = new SimpleReactiveCacheProvider().getCache("r");

        StepVerifier.create(cache.get(key("r", 1))).verifyComplete(); // 미스는 빈 Mono

        StepVerifier.create(cache.put(key("r", 1), "one")).verifyComplete();
        StepVerifier.create(cache.get(key("r", 1))).expectNext("one").verifyComplete();
        assertEquals(1, cache.size());
    }

    @Test
    void evictRemovesEntry() {
        ReactiveCache cache = new SimpleReactiveCacheProvider().getCache("r");
        cache.put(key("r", 1), "one").block();

        StepVerifier.create(cache.evict(key("r", 1))).verifyComplete();
        StepVerifier.create(cache.get(key("r", 1))).verifyComplete();
        assertEquals(0, cache.size());
    }

    @Test
    void putNullIsIgnored() {
        ReactiveCache cache = new SimpleReactiveCacheProvider().getCache("r");
        StepVerifier.create(cache.put(key("r", 1), null)).verifyComplete();
        assertEquals(0, cache.size());
    }

    @Test
    void regionsAreIsolatedAndStable() {
        SimpleReactiveCacheProvider provider = new SimpleReactiveCacheProvider();
        ReactiveCache a = provider.getCache("A");
        ReactiveCache b = provider.getCache("B");

        assertNotSame(a, b);
        assertSame(a, provider.getCache("A"), "같은 region 이름은 같은 인스턴스를 반환해야 한다");

        a.put(key("A", 1), "in-A").block();
        StepVerifier.create(b.get(key("B", 1))).verifyComplete(); // B region은 A의 값을 보지 못한다
        StepVerifier.create(a.get(key("A", 1))).expectNext("in-A").verifyComplete();
    }

    @Test
    void clearAllEmptiesEveryRegion() {
        SimpleReactiveCacheProvider provider = new SimpleReactiveCacheProvider();
        provider.getCache("A").put(key("A", 1), "a").block();
        provider.getCache("B").put(key("B", 1), "b").block();

        StepVerifier.create(provider.clearAll()).verifyComplete();
        assertEquals(0, provider.getCache("A").size());
        assertEquals(0, provider.getCache("B").size());
    }

    @Test
    void ttlExpiresEntry() throws InterruptedException {
        ReactiveCache cache = new SimpleReactiveCacheProvider(CacheOptions.ttl(Duration.ofMillis(40))).getCache("r");
        cache.put(key("r", 1), "one").block();

        StepVerifier.create(cache.get(key("r", 1))).expectNext("one").verifyComplete();
        Thread.sleep(80);
        StepVerifier.create(cache.get(key("r", 1))).verifyComplete(); // 만료되어 미스
    }

    @Test
    void maxSizeEvictsLeastRecentlyUsed() {
        ReactiveCache cache = new SimpleReactiveCacheProvider(CacheOptions.maxSize(2)).getCache("r");
        cache.put(key("r", 1), "one").block();
        cache.put(key("r", 2), "two").block();
        // 1을 접근해 최근 사용으로 승격 → 다음 삽입 시 2가 제거되어야 한다.
        cache.get(key("r", 1)).block();
        cache.put(key("r", 3), "three").block();

        assertEquals(2, cache.size());
        StepVerifier.create(cache.get(key("r", 2))).verifyComplete(); // LRU로 제거됨
        StepVerifier.create(cache.get(key("r", 1))).expectNext("one").verifyComplete();
        StepVerifier.create(cache.get(key("r", 3))).expectNext("three").verifyComplete();
    }
}
