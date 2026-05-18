package io.nova.query;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 집계 결과 한 행을 표현하는 immutable wrapper다. {@code column name -> value} 매핑을 입력 순서대로
 * 보존하며, 그룹 컬럼과 집계 alias가 모두 포함된다.
 * <p>
 * column name lookup은 대소문자에 민감하다 (드라이버가 반환하는 이름을 그대로 사용한다).
 */
public final class AggregateRow {
    private final LinkedHashMap<String, Object> values;

    public AggregateRow(LinkedHashMap<String, Object> values) {
        Objects.requireNonNull(values, "values must not be null");
        this.values = new LinkedHashMap<>(values);
    }

    /**
     * column alias에 해당하는 값을 반환한다. column이 결과에 없으면 {@code null}을 반환한다.
     */
    public Object get(String column) {
        Objects.requireNonNull(column, "column must not be null");
        return values.get(column);
    }

    /**
     * column alias에 해당하는 값을 지정한 타입으로 반환한다. 값이 null이면 그대로 null을 반환한다.
     * 캐스팅 가능 여부는 호출자가 책임진다 — driver가 반환한 타입과 다르면 {@link ClassCastException}이
     * 던져진다.
     */
    @SuppressWarnings("unchecked")
    public <T> T get(String column, Class<T> type) {
        Objects.requireNonNull(column, "column must not be null");
        Objects.requireNonNull(type, "type must not be null");
        Object value = values.get(column);
        if (value == null) {
            return null;
        }
        if (!type.isInstance(value)) {
            throw new ClassCastException("Column " + column + " value of type " + value.getClass().getName()
                    + " is not assignable to " + type.getName());
        }
        return (T) value;
    }

    /**
     * 모든 column 값을 입력 순서대로 immutable view로 반환한다.
     */
    public Map<String, Object> asMap() {
        return Map.copyOf(values);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof AggregateRow row)) {
            return false;
        }
        return values.equals(row.values);
    }

    @Override
    public int hashCode() {
        return values.hashCode();
    }

    @Override
    public String toString() {
        return "AggregateRow" + values;
    }
}
