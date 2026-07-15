package io.nova.spring.data.derived;

/**
 * derived query 메서드가 {@link io.nova.query.Pageable} 파라미터를 받을 때 그 결과를 어떤 페이징
 * 컨테이너로 감싸 반환할지를 나타낸다. 반환 타입의 제네릭 형태({@code Flux<T>} / {@code Mono<Page<T>>} /
 * {@code Mono<Slice<T>>})에서 {@link DerivedQueryParser}가 결정한다.
 *
 * <p>모든 non-{@link #NONE} 값은 {@link Subject#FIND_ALL}에서만 유효하다 — count/exists/delete는
 * 페이지를 반환하지 않으므로 파싱 시점에 거부된다.
 */
enum PagingResult {
    /** Pageable 파라미터 없음 — 기존 non-paged 동작. */
    NONE,
    /** {@code Flux<T>} + Pageable — LIMIT/OFFSET만 적용한 한 페이지 행을 스트리밍한다(총계·hasNext 없음). */
    FLUX,
    /** {@code Mono<Page<T>>} — content + 별도 {@code COUNT(*)} 쿼리로 계산한 totalElements. */
    PAGE,
    /** {@code Mono<Slice<T>>} — content + {@code limit+1} fetch로 판정한 hasNext(총계 쿼리 없음). */
    SLICE
}
