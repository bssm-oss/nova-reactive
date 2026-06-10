package io.nova.spring.data.derived;

import io.nova.query.Sort;

/**
 * {@code OrderBy} 절에서 파싱된 단일 정렬 항목. 방향이 명시되지 않은 경우는 ascending이 기본이다.
 *
 * @param propertyName 정렬 대상 엔티티 프로퍼티 이름.
 * @param direction    {@link Sort.Direction#ASC} 또는 {@link Sort.Direction#DESC}.
 */
record Ordering(String propertyName, Sort.Direction direction) {
}
