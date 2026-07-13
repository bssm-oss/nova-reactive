package io.nova.cache.spi;

import java.util.Objects;

/**
 * 2차 캐시 엔트리를 식별하는 키. region 안에서 (엔티티 타입 + 식별자 값)으로 유일하다.
 *
 * <p>{@code id}는 {@code findById(Class, id)}에 전달되는 형태와 동일하다 — 단일 키는 스칼라, 복합키는
 * {@code @EmbeddedId} holder 또는 {@code @IdClass} 인스턴스다. 따라서 복합키 캐싱은 해당 id 타입의
 * {@code equals}/{@code hashCode} 구현에 의존한다(미구현 시 캐시가 히트하지 않을 뿐, 정합성은 안전).
 *
 * @param region     캐시 region 이름
 * @param entityType 엔티티 타입
 * @param id         식별자 값(findById에 전달하는 형태)
 */
public record CacheKey(String region, Class<?> entityType, Object id) {

    public CacheKey {
        Objects.requireNonNull(region, "region must not be null");
        Objects.requireNonNull(entityType, "entityType must not be null");
        Objects.requireNonNull(id, "id must not be null");
    }
}
