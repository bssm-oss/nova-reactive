package io.nova.query;

import java.util.List;
import java.util.Objects;

/**
 * 다음 페이지 존재 여부({@code hasNext})만 알려주는 가벼운 페이징 결과. 총 행 수를 위한 별도
 * {@code count(*)} 쿼리를 발행하지 않으므로, 사용자가 "다음 페이지가 있는지"만 확인하면 되는
 * infinite scroll 같은 유즈케이스에 적합하다.
 * <p>
 * 구현 측에서는 일반적으로 {@code limit + 1}만큼 행을 더 조회한 뒤, 실제로 {@code limit}을 초과한
 * 행이 있으면 {@code hasNext = true}로 설정하고 {@code content}는 정확히 {@code limit}개로 잘라
 * 노출한다.
 * <p>
 * {@code content}는 생성 시점에 {@link List#copyOf(java.util.Collection)}로 방어 복사된 불변
 * 리스트이며, 호출자가 입력 리스트를 이후에 수정해도 영향을 받지 않는다.
 */
public record Slice<T>(List<T> content, Pageable pageable, boolean hasNext) {
    public Slice {
        Objects.requireNonNull(content, "content must not be null");
        Objects.requireNonNull(pageable, "pageable must not be null");
        content = List.copyOf(content);
    }

    /**
     * 0-based 페이지 번호.
     */
    public int number() {
        return (int) (pageable.offset() / pageable.limit());
    }

    /**
     * 실제 반환된 {@code content}의 크기. 마지막 페이지에서는 {@code pageable.limit()}보다 작을 수 있다.
     */
    public int size() {
        return content.size();
    }

    /**
     * 이전 페이지 존재 여부. 첫 페이지({@code number() == 0})면 {@code false}.
     */
    public boolean hasPrevious() {
        return number() > 0;
    }

    /**
     * {@code content}가 비어 있는지 여부.
     */
    public boolean isEmpty() {
        return content.isEmpty();
    }
}
