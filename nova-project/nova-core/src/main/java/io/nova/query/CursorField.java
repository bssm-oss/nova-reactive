package io.nova.query;

import java.util.Objects;

/**
 * keyset(cursor) pagination에서 한 정렬 키의 마지막 값과 방향을 표현한다.
 * <p>
 * {@code property}는 엔티티 메타데이터에 존재해야 하며, {@code lastValue}는 마지막으로 본 행의 해당 컬럼
 * 값이다. {@code direction}은 ORDER BY 방향과 cursor 비교 부호({@code >} vs {@code <})를 결정한다.
 * <p>
 * {@code lastValue}가 {@code null}이면 cursor 비교가 SQL 3-valued logic 상 unknown으로 떨어져
 * 다음 페이지가 비게 되므로 빌드 시점에 거부한다. NULL이 정렬 키로 의미가 필요하면 호출자가
 * 정렬 표현식을 비-NULL로 정규화해야 한다.
 */
public record CursorField(String property, Object lastValue, Sort.Direction direction) {
    public CursorField {
        Objects.requireNonNull(property, "property must not be null");
        if (property.isBlank()) {
            throw new IllegalArgumentException("property must not be blank");
        }
        Objects.requireNonNull(lastValue, "lastValue must not be null");
        Objects.requireNonNull(direction, "direction must not be null");
    }

    public static CursorField asc(String property, Object lastValue) {
        return new CursorField(property, lastValue, Sort.Direction.ASC);
    }

    public static CursorField desc(String property, Object lastValue) {
        return new CursorField(property, lastValue, Sort.Direction.DESC);
    }
}
