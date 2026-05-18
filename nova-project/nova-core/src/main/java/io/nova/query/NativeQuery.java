package io.nova.query;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * SQL 문자열과 positional binding을 묶어 raw query 한 건을 표현하는 immutable value type.
 * {@code ReactiveEntityOperations}의 {@code executeNative}/{@code queryNative}/{@code queryNativeOne}
 * 진입점에서 사용한다.
 * <p>
 * SQL은 dialect의 {@link io.nova.sql.BindMarkerStrategy}에 맞춰 호출자가 직접 작성한다.
 * binding 순서는 SQL 안의 marker 출현 순서를 따른다.
 * <p>
 * 인스턴스는 Java 21 {@code record}이며, {@link #equals(Object)}/{@link #hashCode()}/
 * {@link #toString()}은 component 단위로 자동 생성된다. {@code bindings}는 canonical
 * constructor에서 {@link List#copyOf(java.util.Collection)}으로 immutable view로 캡처되므로
 * accessor 반환 값에 대한 변경 시도는 {@link UnsupportedOperationException}을 던진다.
 *
 * @param sql      실행할 SQL 문자열. {@code null}/blank는 거부된다.
 * @param bindings positional binding 목록. {@code null} 목록은 거부되지만 개별 element는
 *                 {@code null}을 허용한다(SQL NULL과 매핑). 방어 카피된다.
 */
public record NativeQuery(String sql, List<Object> bindings) {
    public NativeQuery {
        Objects.requireNonNull(sql, "sql must not be null");
        Objects.requireNonNull(bindings, "bindings must not be null");
        if (sql.isBlank()) {
            throw new IllegalArgumentException("sql must not be blank");
        }
        bindings = List.copyOf(bindings);
    }

    /**
     * binding 없이 SQL만 가진 새 query를 만든다.
     */
    public static NativeQuery of(String sql) {
        return new NativeQuery(sql, List.of());
    }

    /**
     * SQL과 binding을 묶어 새 query를 만든다. {@code bindings}는 방어 카피된다.
     */
    public static NativeQuery of(String sql, List<Object> bindings) {
        return new NativeQuery(sql, bindings);
    }

    /**
     * 기존 binding 뒤에 {@code value}를 추가한 새 {@code NativeQuery}를 반환한다.
     * 원본 인스턴스는 변경되지 않는다. {@code value}는 SQL NULL과 매핑되도록 {@code null}을 허용한다.
     */
    public NativeQuery bind(Object value) {
        List<Object> next = new ArrayList<>(bindings.size() + 1);
        next.addAll(bindings);
        next.add(value);
        return new NativeQuery(sql, next);
    }
}
