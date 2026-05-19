package io.nova.query;

/**
 * 0-based page-number 의미론으로 페이징 요청을 표현하는 record.
 *
 * <p>{@link Pageable}이 {@code limit}/{@code offset}을 그대로 받는 저수준 표현인 반면,
 * {@code PageRequest}는 사용자 친화적인 {@code page}와 {@code size} 쌍으로 동일한 의도를
 * 표현한다. 두 record가 공존하는 이유는, 기존 {@code Pageable.of(int limit, long offset)}와
 * 페이지 번호용 {@code of(int page, int size)}를 한 자리에서 오버로드하면 두 번째 인자가
 * 자동으로 {@code long}으로 승격되며 시그니처 모호함을 일으킬 수 있기 때문이다. page-number
 * 의미론은 별도 타입에 격리하여 가독성과 하위 호환성을 동시에 확보한다.
 *
 * <p>{@link #sort()}는 선택 사항이며 {@code null}이 허용된다. {@code null}인 경우 정렬을
 * 지정하지 않은 것으로 간주한다.
 */
public record PageRequest(int page, int size, Sort sort) {
    public PageRequest {
        if (page < 0) {
            throw new IllegalArgumentException("page must be at least 0");
        }
        if (size < 1) {
            throw new IllegalArgumentException("size must be greater than 0");
        }
    }

    /**
     * 정렬을 지정하지 않은 {@code PageRequest}를 생성한다.
     *
     * @param page 0부터 시작하는 페이지 번호, 음수 불가
     * @param size 한 페이지의 항목 수, 1 이상
     */
    public static PageRequest of(int page, int size) {
        return new PageRequest(page, size, null);
    }

    /**
     * 정렬과 함께 {@code PageRequest}를 생성한다.
     *
     * @param page 0부터 시작하는 페이지 번호, 음수 불가
     * @param size 한 페이지의 항목 수, 1 이상
     * @param sort 정렬 사양, {@code null} 허용
     */
    public static PageRequest of(int page, int size, Sort sort) {
        return new PageRequest(page, size, sort);
    }

    /**
     * 저수준 {@link Pageable}로 변환한다.
     *
     * <p>offset은 {@code page * size}로 계산하며, 한쪽 피연산자를 {@code long}으로 캐스팅하여
     * {@code int} 곱셈 오버플로를 회피한다. 예를 들어 {@code page=100_000_000, size=50}처럼
     * 곱셈 결과가 {@code int} 범위를 넘는 경우에도 안전하게 계산된다.
     */
    public Pageable toPageable() {
        long offset = (long) page * (long) size;
        return new Pageable(size, offset);
    }

    /**
     * 다음 페이지를 가리키는 새 {@code PageRequest}를 반환한다. {@code size}와 {@code sort}는
     * 보존된다.
     */
    public PageRequest next() {
        return new PageRequest(page + 1, size, sort);
    }

    /**
     * 이전 페이지를 가리키는 새 {@code PageRequest}를 반환한다. 현재 페이지가 0이면 자기
     * 자신을 반환하여 음수 페이지 진입을 방지한다.
     */
    public PageRequest previous() {
        if (page == 0) {
            return this;
        }
        return new PageRequest(page - 1, size, sort);
    }

    /**
     * 첫 페이지(page=0)를 가리키는 새 {@code PageRequest}를 반환한다. {@code size}와
     * {@code sort}는 보존된다.
     */
    public PageRequest first() {
        return new PageRequest(0, size, sort);
    }
}
