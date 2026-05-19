package io.nova.query;

import java.util.List;
import java.util.Objects;

/**
 * 페이징 조회 결과를 감싸는 immutable 컨테이너. 한 페이지의 {@code content}와 총 행 수
 * ({@code totalElements}), 그리고 요청 페이지 정보({@link Pageable})를 묶어 노출한다.
 * <p>
 * {@link Pageable}이 {@code null}이면 단일 페이지 결과로 취급하며,
 * {@link #number()}는 {@code 0}, {@link #size()}는 {@code content.size()}로 동작한다. 이 경우
 * {@link #hasNext()}/{@link #hasPrevious()}는 항상 {@code false}다.
 * <p>
 * {@code content}는 생성 시점에 {@link List#copyOf(java.util.Collection)}로 방어 복사된 불변
 * 리스트이며, 호출자가 입력 리스트를 이후에 수정해도 영향을 받지 않는다.
 */
public record Page<T>(List<T> content, long totalElements, Pageable pageable) {
    public Page {
        Objects.requireNonNull(content, "content must not be null");
        content = List.copyOf(content);
        if (totalElements < 0) {
            throw new IllegalArgumentException("totalElements must be at least 0");
        }
    }

    /**
     * 0-based 페이지 번호. {@code pageable}이 {@code null}이면 항상 {@code 0}이다.
     */
    public int number() {
        return pageable == null ? 0 : (int) (pageable.offset() / pageable.limit());
    }

    /**
     * 요청된 페이지 크기. {@code pageable}이 {@code null}이면 실제 {@code content} 크기를 반환한다.
     */
    public int size() {
        return pageable == null ? content.size() : pageable.limit();
    }

    /**
     * {@code totalElements}와 {@link #size()}로 계산한 총 페이지 수. {@code size()}가 {@code 0}이면
     * {@code 0}을 반환한다(division-by-zero 방지).
     */
    public int totalPages() {
        return size() == 0 ? 0 : (int) Math.ceil((double) totalElements / size());
    }

    /**
     * 다음 페이지 존재 여부. {@code pageable}이 {@code null}이거나 현재가 마지막 페이지면 {@code false}.
     */
    public boolean hasNext() {
        return pageable != null && number() + 1 < totalPages();
    }

    /**
     * 이전 페이지 존재 여부. {@code pageable}이 {@code null}이거나 현재가 첫 페이지면 {@code false}.
     */
    public boolean hasPrevious() {
        return pageable != null && number() > 0;
    }

    /**
     * 페이지 {@code content}가 비어 있는지 여부.
     */
    public boolean isEmpty() {
        return content.isEmpty();
    }
}
