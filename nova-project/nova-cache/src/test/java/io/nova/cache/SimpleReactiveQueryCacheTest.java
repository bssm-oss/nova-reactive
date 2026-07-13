package io.nova.cache;

import io.nova.cache.spi.ReactiveQueryCache;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link SimpleReactiveQueryCache}의 get/put/invalidate/clear, 타입별 분리 무효화, 빈 결과 히트, TTL 만료를
 * 검증한다. 모든 시나리오는 {@link StepVerifier}로 리액티브 계약을 고정한다.
 */
class SimpleReactiveQueryCacheTest {

    static class Person {
    }

    static class Order {
    }

    @Test
    void putThenGetReturnsStoredResult() {
        ReactiveQueryCache cache = new SimpleReactiveQueryCache();
        cache.put(Person.class, "q1", List.of("a", "b")).block();

        StepVerifier.create(cache.get(Person.class, "q1"))
                .assertNext(list -> assertEquals(List.of("a", "b"), list))
                .verifyComplete();
    }

    @Test
    void missReturnsEmptyMono() {
        ReactiveQueryCache cache = new SimpleReactiveQueryCache();
        StepVerifier.create(cache.get(Person.class, "absent")).verifyComplete();
    }

    @Test
    void emptyResultListIsAHitNotAMiss() {
        // 빈 결과 집합도 히트로 취급돼야 재조회를 유발하지 않는다(빈 리스트 ≠ 미스).
        ReactiveQueryCache cache = new SimpleReactiveQueryCache();
        cache.put(Person.class, "empty", List.of()).block();

        StepVerifier.create(cache.get(Person.class, "empty"))
                .assertNext(list -> assertEquals(List.of(), list))
                .verifyComplete();
    }

    @Test
    void invalidateClearsOnlyThatEntityType() {
        SimpleReactiveQueryCache cache = new SimpleReactiveQueryCache();
        cache.put(Person.class, "q", List.of("p")).block();
        cache.put(Order.class, "q", List.of("o")).block();

        cache.invalidate(Person.class).block();

        StepVerifier.create(cache.get(Person.class, "q")).verifyComplete(); // 무효화됨
        StepVerifier.create(cache.get(Order.class, "q"))                     // 다른 타입은 유지
                .assertNext(list -> assertEquals(List.of("o"), list))
                .verifyComplete();
    }

    @Test
    void clearRemovesEverything() {
        SimpleReactiveQueryCache cache = new SimpleReactiveQueryCache();
        cache.put(Person.class, "q", List.of("p")).block();
        cache.put(Order.class, "q", List.of("o")).block();

        cache.clear().block();

        StepVerifier.create(cache.get(Person.class, "q")).verifyComplete();
        StepVerifier.create(cache.get(Order.class, "q")).verifyComplete();
        assertEquals(0, cache.regionCount());
    }

    @Test
    void putSnapshotIsIsolatedFromCallerMutation() {
        SimpleReactiveQueryCache cache = new SimpleReactiveQueryCache();
        java.util.List<Object> mutable = new java.util.ArrayList<>(List.of("a"));
        cache.put(Person.class, "q", mutable).block();
        mutable.add("b"); // put 이후 호출자가 변형해도 캐시 스냅샷은 격리돼야 한다.

        StepVerifier.create(cache.get(Person.class, "q"))
                .assertNext(list -> assertEquals(List.of("a"), list))
                .verifyComplete();
    }

    @Test
    void ttlExpiresEntry() throws InterruptedException {
        ReactiveQueryCache cache = new SimpleReactiveQueryCache(CacheOptions.ttl(Duration.ofMillis(30)));
        cache.put(Person.class, "q", List.of("p")).block();
        Thread.sleep(60);
        StepVerifier.create(cache.get(Person.class, "q")).verifyComplete(); // 만료 → 미스
    }

    @Test
    void maxSizeEvictsLeastRecentlyUsedWithinType() {
        SimpleReactiveQueryCache cache = new SimpleReactiveQueryCache(CacheOptions.maxSize(2));
        cache.put(Person.class, "k1", List.of("1")).block();
        cache.put(Person.class, "k2", List.of("2")).block();
        cache.get(Person.class, "k1").block();               // k1 최근 사용
        cache.put(Person.class, "k3", List.of("3")).block(); // 초과 → LRU(k2) 제거

        StepVerifier.create(cache.get(Person.class, "k2")).verifyComplete();
        StepVerifier.create(cache.get(Person.class, "k1"))
                .assertNext(list -> assertEquals(List.of("1"), list))
                .verifyComplete();
        assertEquals(2, cache.size(Person.class));
    }
}
