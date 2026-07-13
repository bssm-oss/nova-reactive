package io.nova.metadata;

import java.util.Objects;

/**
 * {@code @NamedQuery}/{@code @NamedNativeQuery}로 엔티티(또는 {@code @MappedSuperclass})에 선언된 명명 쿼리
 * 한 건을 표현하는 immutable value type. {@link EntityMetadataFactory#namedQueryDefinitions(Class)}가
 * 애너테이션을 파싱해 이 정의들을 발행하고, JPQL 서브시스템의 레지스트리가 이를 이름으로 등록·조회·실행한다.
 * <p>
 * {@link #nativeQuery()}가 {@code false}이면 {@link #query()}는 JPQL 문자열이고 {@link #resultClass()}는
 * {@code null}이다. {@code true}이면 {@link #query()}는 네이티브 SQL이며 {@link #resultClass()}는
 * 결과 매핑 대상 엔티티 타입(없으면 {@code null})이다.
 *
 * @param name        전역 고유 명명 쿼리 이름(대소문자 구분, JPA 규약)
 * @param query       JPQL 또는 네이티브 SQL 문자열
 * @param nativeQuery {@code @NamedNativeQuery}면 {@code true}, {@code @NamedQuery}면 {@code false}
 * @param resultClass 네이티브 쿼리의 결과 엔티티 타입(선택). JPQL이면 {@code null}
 */
public record NamedQueryDefinition(String name, String query, boolean nativeQuery, Class<?> resultClass) {

    public NamedQueryDefinition {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(query, "query must not be null");
        if (name.isBlank()) {
            throw new IllegalArgumentException("named query name must not be blank");
        }
        if (query.isBlank()) {
            throw new IllegalArgumentException("named query '" + name + "' must not have a blank query string");
        }
        if (!nativeQuery && resultClass != null) {
            throw new IllegalArgumentException(
                    "named JPQL query '" + name + "' must not declare a native resultClass");
        }
    }
}
