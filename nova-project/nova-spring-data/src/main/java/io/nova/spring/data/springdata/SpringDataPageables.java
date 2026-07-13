package io.nova.spring.data.springdata;

import io.nova.query.Page;
import io.nova.query.Pageable;
import io.nova.query.Slice;

import java.util.List;

/**
 * Spring Data Commons {@code org.springframework.data.domain.Pageable}/{@code Page}/{@code Slice}와
 * Nova 자체 {@link Pageable}/{@link Page}/{@link Slice} 사이의 양방향 브릿지다.
 *
 * <p>{@link SpringDataSorts}와 마찬가지로 {@code spring-data-commons}에 대해 <b>optional</b>
 * (compileOnly)로 컴파일된다.
 *
 * <h2>지원 및 근사 범위</h2>
 * <ul>
 *   <li>Paged Spring {@code Pageable}은 {@code pageSize}→{@code limit}, {@code offset}→{@code offset}
 *       으로 Nova {@link Pageable}에 매핑된다. 정렬은 {@link SpringDataSorts}가 별도로 변환한다.</li>
 *   <li>{@code Pageable.unpaged()}는 "페이지 제한 없음"을 뜻하므로 Nova {@code null}
 *       {@link Pageable}로 매핑된다(호출부에서 전체 조회로 위임).</li>
 *   <li>역방향: Nova {@link Page}/{@link Slice}는 요청 시점의 Spring {@code Pageable}을 함께
 *       넘기면 정렬·페이지 정보까지 보존한 Spring {@code Page}/{@code Slice}로 변환된다.</li>
 * </ul>
 */
public final class SpringDataPageables {

    private SpringDataPageables() {
    }

    /**
     * Spring Data {@code Pageable}을 Nova {@link Pageable}로 변환한다. 정렬은 포함하지 않는다
     * ({@link SpringDataSorts#toNova}로 별도 변환).
     *
     * @param springPageable Spring Data 페이지 요청. {@code null}이거나 {@code unpaged()}이면
     *                       "페이지 제한 없음"을 뜻하는 {@code null}을 반환한다.
     * @return Nova {@link Pageable}, unpaged면 {@code null}
     */
    public static Pageable toNova(org.springframework.data.domain.Pageable springPageable) {
        if (springPageable == null || springPageable.isUnpaged()) {
            return null;
        }
        return Pageable.of(springPageable.getPageSize(), springPageable.getOffset());
    }

    /**
     * Nova {@link Page}를 Spring Data {@code Page}로 변환한다. 요청 시점의 Spring
     * {@code Pageable}(정렬 포함)을 그대로 실어 반환한다.
     *
     * @param novaPage        Nova 페이지 결과
     * @param requestPageable 원 요청에 사용된 Spring {@code Pageable}(정렬 보존용)
     * @param <T>             엔티티 타입
     * @return Spring Data {@code Page}
     */
    public static <T> org.springframework.data.domain.Page<T> toSpring(
            Page<T> novaPage, org.springframework.data.domain.Pageable requestPageable) {
        List<T> content = novaPage.content();
        return new org.springframework.data.domain.PageImpl<>(
                content, requestPageable, novaPage.totalElements());
    }

    /**
     * Nova {@link Page}를 Spring Data {@code Page}로 변환한다. Nova {@link Page}에 담긴
     * {@code number}/{@code size}로부터 Spring {@code Pageable}을 재구성한다(정렬 정보는
     * Nova {@link Page}가 보존하지 않으므로 unsorted).
     *
     * @param novaPage Nova 페이지 결과
     * @param <T>      엔티티 타입
     * @return Spring Data {@code Page}
     */
    public static <T> org.springframework.data.domain.Page<T> toSpring(Page<T> novaPage) {
        return toSpring(novaPage, springPageableOf(novaPage.number(), novaPage.size()));
    }

    /**
     * Nova {@link Slice}를 Spring Data {@code Slice}로 변환한다. 요청 시점의 Spring
     * {@code Pageable}(정렬 포함)을 그대로 실어 반환한다.
     *
     * @param novaSlice       Nova 슬라이스 결과
     * @param requestPageable 원 요청에 사용된 Spring {@code Pageable}(정렬 보존용)
     * @param <T>             엔티티 타입
     * @return Spring Data {@code Slice}
     */
    public static <T> org.springframework.data.domain.Slice<T> toSpring(
            Slice<T> novaSlice, org.springframework.data.domain.Pageable requestPageable) {
        List<T> content = novaSlice.content();
        return new org.springframework.data.domain.SliceImpl<>(
                content, requestPageable, novaSlice.hasNext());
    }

    /**
     * Nova {@link Slice}를 Spring Data {@code Slice}로 변환한다. Nova {@link Slice}에 담긴
     * {@code number}/{@code size}로부터 Spring {@code Pageable}을 재구성한다.
     *
     * @param novaSlice Nova 슬라이스 결과
     * @param <T>       엔티티 타입
     * @return Spring Data {@code Slice}
     */
    public static <T> org.springframework.data.domain.Slice<T> toSpring(Slice<T> novaSlice) {
        int size = novaSlice.pageable().limit();
        int number = novaSlice.number();
        return toSpring(novaSlice, springPageableOf(number, size));
    }

    /**
     * {@code number}(0-based)/{@code size}로 Spring {@code PageRequest}를 만든다. {@code size < 1}
     * (내용이 비고 pageable이 없는 단일 페이지)면 {@code Pageable.unpaged()}로 매핑한다.
     */
    private static org.springframework.data.domain.Pageable springPageableOf(int number, int size) {
        if (size < 1) {
            return org.springframework.data.domain.Pageable.unpaged();
        }
        return org.springframework.data.domain.PageRequest.of(number, size);
    }
}
