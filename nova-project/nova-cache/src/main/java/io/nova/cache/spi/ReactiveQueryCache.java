package io.nova.cache.spi;

import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 쿼리 결과 캐시 SPI (v1 미배선 — SPI만 개방). JPQL/QuerySpec 실행 결과(엔티티 id 리스트 또는 스칼라)를
 * 쿼리 텍스트 + 바인딩 파라미터로 키잉해 캐시한다. 실제 read-through 배선은 v2에서 JPQL 실행 경로와 함께
 * 추가되며, v1에서는 커스텀 구현이 직접 사용할 수 있도록 계약만 노출한다.
 *
 * <p>정합성 주의: 쿼리 캐시는 엔티티 캐시보다 무효화 범위가 넓다 — 대상 테이블에 write가 발생하면 관련
 * 쿼리 결과를 모두 무효화해야 한다. v1 기본 제공 구현이 없는 이유이며, 사용자는 자신의 무효화 정책과 함께
 * 이 SPI를 구현해야 한다.
 */
public interface ReactiveQueryCache {

    /**
     * 캐시된 쿼리 결과 키 목록(엔티티 식별자 등)을 조회한다. 미스이면 빈 {@link Mono}.
     *
     * @param queryKey 쿼리 텍스트 + 정규화된 바인딩으로 구성한 캐시 키
     */
    Mono<List<Object>> get(String queryKey);

    /**
     * 쿼리 결과 키 목록을 저장한다.
     */
    Mono<Void> put(String queryKey, List<Object> resultKeys);

    /**
     * 특정 엔티티 타입과 연관된 쿼리 결과를 모두 무효화한다. write invalidation 진입점이다.
     */
    Mono<Void> invalidate(Class<?> entityType);

    /**
     * 모든 쿼리 결과를 비운다.
     */
    Mono<Void> clear();
}
