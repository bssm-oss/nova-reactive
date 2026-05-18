package io.nova.query;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * SQL 문자열과 positional binding을 묶어 raw query 한 건을 표현하는 immutable value type.
 * {@code ReactiveEntityOperations}의 {@code executeNative}/{@code queryNative}/{@code queryNativeOne}
 * 진입점에서 사용한다.
 * <p>
 * SQL은 dialect의 {@link io.nova.sql.BindMarkerStrategy}에 맞춰 호출자가 직접 작성한다.
 * binding 순서는 SQL 안의 marker 출현 순서를 따른다.
 */
public final class NativeQuery {
    private final String sql;
    private final List<Object> bindings;

    private NativeQuery(String sql, List<Object> bindings) {
        this.sql = sql;
        this.bindings = bindings;
    }

    /**
     * binding 없이 SQL만 가진 새 query를 만든다.
     */
    public static NativeQuery of(String sql) {
        return of(sql, List.of());
    }

    /**
     * SQL과 binding을 묶어 새 query를 만든다. {@code bindings}는 방어 카피된다.
     */
    public static NativeQuery of(String sql, List<Object> bindings) {
        Objects.requireNonNull(sql, "sql must not be null");
        Objects.requireNonNull(bindings, "bindings must not be null");
        if (sql.isBlank()) {
            throw new IllegalArgumentException("sql must not be blank");
        }
        return new NativeQuery(sql, List.copyOf(bindings));
    }

    /**
     * 기존 binding 뒤에 {@code value}를 추가한 새 {@code NativeQuery}를 반환한다.
     * 원본 인스턴스는 변경되지 않는다.
     */
    public NativeQuery bind(Object value) {
        List<Object> next = new ArrayList<>(bindings.size() + 1);
        next.addAll(bindings);
        next.add(value);
        return new NativeQuery(sql, Collections.unmodifiableList(next));
    }

    public String sql() {
        return sql;
    }

    public List<Object> bindings() {
        return bindings;
    }
}
