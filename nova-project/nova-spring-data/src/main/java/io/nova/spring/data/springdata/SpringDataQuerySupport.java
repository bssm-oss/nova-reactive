package io.nova.spring.data.springdata;

import io.nova.query.Pageable;

import java.util.List;

/**
 * {@code @Query} 실행 경로에서 표준 Spring Data {@code Pageable}/{@code Page}/{@code Slice}를 다루는
 * <b>Object 시그니처</b> 헬퍼다. 모든 public 메서드는 Spring 타입을 파라미터/반환에 노출하지 않고
 * {@code Object}로만 주고받으며 내부에서만 캐스팅한다.
 *
 * <p><b>격리(isolation) 계약:</b> 이 덕분에 {@code io.nova.spring.data.query} 패키지의 실행기는
 * 자신의 bytecode에 {@code org.springframework.data.domain.*} 타입 참조를 갖지 않는다 — Spring 타입은
 * 오직 이 클래스(및 같은 패키지의 {@link SpringDataPageables})가 실제로 호출될 때만 로드된다. 따라서
 * Spring Data 표준 타입을 쓰지 않는 소비자의 코드 경로에서는 {@code spring-data-commons}가 런타임
 * 클래스패스에 없어도 안전하다. {@link SpringDataPageables}와 동일하게 {@code compileOnly}로 컴파일된다.
 */
public final class SpringDataQuerySupport {

    private SpringDataQuerySupport() {
    }

    /**
     * Spring {@code Pageable}(Object)을 Nova {@link Pageable}로 변환한다. {@code unpaged()}이면
     * "페이지 제한 없음"을 뜻하는 {@code null}을 반환한다.
     */
    public static Pageable toNovaPageable(Object springPageable) {
        return SpringDataPageables.toNova((org.springframework.data.domain.Pageable) springPageable);
    }

    /**
     * Spring {@code Pageable}(Object)에 정렬이 지정되어 있으면 {@code true}. {@code @Query}는 정렬을
     * 쿼리 문자열의 {@code ORDER BY}로 표현해야 하므로, 이 경우 호출자가 fail-fast 한다.
     */
    public static boolean pageableHasSort(Object springPageable) {
        return ((org.springframework.data.domain.Pageable) springPageable).getSort().isSorted();
    }

    /**
     * Spring {@code Sort}(Object)가 실제 정렬을 담고 있으면 {@code true}.
     */
    public static boolean sortIsSorted(Object springSort) {
        return ((org.springframework.data.domain.Sort) springSort).isSorted();
    }

    /**
     * content/total/원 {@code Pageable}로 Spring {@code Page}(Object)를 만든다.
     */
    public static <T> Object springPage(List<T> content, long total, Object springPageable) {
        return new org.springframework.data.domain.PageImpl<>(
                content, (org.springframework.data.domain.Pageable) springPageable, total);
    }

    /**
     * content/hasNext/원 {@code Pageable}로 Spring {@code Slice}(Object)를 만든다.
     */
    public static <T> Object springSlice(List<T> content, boolean hasNext, Object springPageable) {
        return new org.springframework.data.domain.SliceImpl<>(
                content, (org.springframework.data.domain.Pageable) springPageable, hasNext);
    }
}
