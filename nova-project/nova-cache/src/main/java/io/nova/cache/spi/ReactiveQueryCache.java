package io.nova.cache.spi;

import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 쿼리 결과 캐시 SPI. {@code findAll(Class, QuerySpec)} 류 조회의 <b>정규화된 스펙 + 바인딩 파라미터</b>를
 * 캐시 키로 삼아, 조회 결과를 통째로(materialized) 저장한다. 히트 시 DB SELECT를 발행하지 않고 캐시된 결과를
 * 그대로 발행한다(read-through).
 *
 * <h2>정합성 계약 (핵심 리스크)</h2>
 * <ul>
 *   <li>쿼리 캐시는 엔티티 캐시보다 무효화 범위가 넓다 — predicate로 특정된 결과 집합은 대상 테이블의
 *       <b>어떤</b> write에도 영향받을 수 있으므로, 해당 엔티티 타입에 write가 발생하면 그 타입의 쿼리 결과를
 *       <b>모두</b> 무효화해야 한다({@link #invalidate(Class)}). 이 SPI를 구현할 때 부분 무효화(어떤 행이 어떤
 *       쿼리 결과에 속하는지 추적)를 시도하지 말고 타입 단위 통째 무효화를 유지하라 — 잘못된 부분 무효화는
 *       stale 결과를 서빙한다.</li>
 *   <li>키는 엔티티 타입별로 분리 저장돼 {@link #invalidate(Class)}가 한 타입의 모든 결과를 비운다.
 *       그래서 get/put에 엔티티 타입을 함께 넘긴다.</li>
 *   <li>트랜잭션 정합성은 데코레이터가 책임진다 — 미커밋 트랜잭션 안에서는 쿼리 캐시를 채우지 않고,
 *       write는 즉시 무효화한 뒤 commit 성공 후 재무효화(post-commit re-evict)해 rollback stale과 동시
 *       reader의 재-populate 창을 막는다.</li>
 * </ul>
 *
 * <p>기본 in-process 구현은 {@link io.nova.cache.SimpleReactiveQueryCache}이며, 외부 캐시(Redis 등)는 이 SPI를
 * 구현해 주입할 수 있다. 구현체는 스레드 안전해야 한다.
 */
public interface ReactiveQueryCache {

    /**
     * 캐시된 쿼리 결과(엔티티 리스트)를 조회한다. 미스이면 빈 {@link Mono}. 빈 결과 집합도 히트로 취급되도록
     * 구현체는 빈 리스트를 값으로 저장/발행할 수 있어야 한다(빈 리스트 ≠ 미스).
     *
     * @param entityType 쿼리 대상 엔티티의 canonical 타입(무효화 스코프 키)
     * @param queryKey   정규화된 스펙 + 바인딩으로 구성한 캐시 키
     */
    Mono<List<Object>> get(Class<?> entityType, String queryKey);

    /**
     * 쿼리 결과(엔티티 리스트)를 저장한다. 이미 존재하면 덮어쓴다.
     *
     * @param entityType 쿼리 대상 엔티티의 canonical 타입(무효화 스코프 키)
     * @param queryKey   정규화된 캐시 키
     * @param results    materialized 결과 엔티티 리스트(빈 리스트 허용)
     */
    Mono<Void> put(Class<?> entityType, String queryKey, List<Object> results);

    /**
     * 특정 엔티티 타입과 연관된 쿼리 결과를 모두 무효화한다. write invalidation 진입점이다.
     */
    Mono<Void> invalidate(Class<?> entityType);

    /**
     * 모든 쿼리 결과를 비운다. 대상 타입을 특정할 수 없는 native/compiled write 후 보수적 전역 무효화에 쓴다.
     */
    Mono<Void> clear();
}
