package io.nova.cache.annotation;

/**
 * 2차 캐시 region의 동시성 전략. JPA/Hibernate {@code @Cache(usage=...)}의 리액티브 등가 개념이며,
 * Nova는 in-process 리액티브 캐시(v1)에서 다음 두 전략만 지원한다.
 *
 * <ul>
 *   <li>{@link #READ_ONLY} — 삽입 후 변경되지 않는 엔티티 전용. 캐시된 값은 evict 되기 전까지 그대로 재사용된다.</li>
 *   <li>{@link #READ_WRITE} — read-through 후 write 시 즉시 evict(보수적 무효화)로 stale을 회피한다. 기본값.</li>
 * </ul>
 *
 * <p>{@link #NONSTRICT_READ_WRITE}와 {@link #TRANSACTIONAL}은 열거값으로만 존재하며 v1에서는 미지원이다 —
 * {@code @Cache}에 지정하면 {@link io.nova.cache.CacheConfigurationResolver}가 fail-fast로 거부한다
 * (조용한 무시 금지). 분산/트랜잭셔널 정합성은 외부 캐시 프로바이더와 함께 v2에서 다룬다.
 */
public enum CacheConcurrencyStrategy {

    /**
     * 불변 엔티티 전용. 캐시 값은 명시적 evict 전까지 갱신되지 않는다. 이 전략의 엔티티에 update가 발생하면
     * {@link io.nova.cache.CachingReactiveEntityOperations}는 여전히 evict를 수행하지만, 애플리케이션이
     * READ_ONLY 규약(삽입 후 불변)을 어긴 것으로 간주한다.
     */
    READ_ONLY,

    /**
     * read-through + write 시 즉시 evict. Nova v1의 기본 전략이며, in-process 캐시에서 stale 회피를 위해
     * 값을 캐시에 다시 채우지 않고 무효화만 한다(commit 후 재-evict로 창을 최소화).
     */
    READ_WRITE,

    /** v1 미지원. 지정 시 fail-fast. */
    NONSTRICT_READ_WRITE,

    /** v1 미지원. 지정 시 fail-fast. */
    TRANSACTIONAL
}
