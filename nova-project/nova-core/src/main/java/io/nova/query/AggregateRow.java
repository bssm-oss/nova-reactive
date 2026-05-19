package io.nova.query;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 집계 결과 한 행을 표현하는 immutable wrapper다. {@code column name -> value} 매핑을 입력 순서대로
 * 보존하며, 그룹 컬럼과 집계 alias가 모두 포함된다.
 * <p>
 * column name lookup은 대소문자에 민감하다 (드라이버가 반환하는 이름을 그대로 사용한다).
 * <p>
 * <b>raw 타입 노출 정책</b>: 값은 R2DBC driver가 반환한 타입을 어떤 변환도 거치지 않고 그대로
 * 보존한다 — {@code @Column}/{@code AttributeConverter} 같은 entity-level converter는 적용되지 않는다.
 * 같은 집계라도 dialect에 따라 driver가 매핑하는 raw Java 타입이 달라질 수 있다. 예를 들어
 * {@code sum(integer column)}은 PostgreSQL에서 {@code Long}, MySQL에서 {@code BigDecimal}로 들어올 수
 * 있고, {@code avg(...)}는 일반적으로 {@code BigDecimal}이며 dialect/컬럼 타입에 따라 달라진다.
 * 따라서 호출자는 사용 중인 dialect의 매핑 규칙을 알고 적절한 타입으로 변환해야 한다.
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
        // Map.copyOf는 insertion order를 보장하지 않으므로 LinkedHashMap을 unmodifiableMap으로 감싼다.
        return Collections.unmodifiableMap(new LinkedHashMap<>(values));
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
