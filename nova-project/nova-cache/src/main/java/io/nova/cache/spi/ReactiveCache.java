package io.nova.cache.spi;

import reactor.core.publisher.Mono;

/**
 * 단일 region에 대한 리액티브 캐시 추상화. 모든 연산은 {@code Mono}를 반환하며 blocking을 노출하지 않는다
 * (AGENTS.md rule #4). 외부 캐시(Redis 등)는 이 SPI를 구현해 비동기 클라이언트로 배선할 수 있다 — v1의
 * 기본 구현 {@link io.nova.cache.SimpleReactiveCache}는 in-process {@code ConcurrentHashMap} 기반이다.
 *
 * <p>구현체는 스레드 안전해야 한다(리액티브 파이프라인이 여러 스케줄러에서 동시에 접근할 수 있다).
 */
public interface ReactiveCache {

    /**
     * region 이름.
     */
    String region();

    /**
     * 캐시에서 값을 조회한다. 미스이면 빈 {@link Mono}, 히트이면 저장된 값을 발행한다. TTL 만료 엔트리는
     * 미스로 취급되며 필요 시 지연 제거된다.
     */
    Mono<Object> get(CacheKey key);

    /**
     * 값을 캐시에 저장한다. 이미 존재하면 덮어쓴다. {@code null} 값 저장은 no-op으로 무시된다(캐시는 부재를
     * 저장하지 않는다).
     */
    Mono<Void> put(CacheKey key, Object value);

    /**
     * 주어진 키의 엔트리를 제거한다. 존재하지 않아도 성공으로 완료한다.
     */
    Mono<Void> evict(CacheKey key);

    /**
     * region의 모든 엔트리를 비운다.
     */
    Mono<Void> clear();

    /**
     * 현재 캐시된 엔트리 수(만료되지 않은 것 기준의 근사값). 통계/테스트 용도이며 blocking이 아니다.
     */
    long size();
}
