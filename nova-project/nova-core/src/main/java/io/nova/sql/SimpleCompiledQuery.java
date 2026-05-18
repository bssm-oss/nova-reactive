package io.nova.sql;

import java.util.List;
import java.util.Objects;

/**
 * SQL 문자열과 parameter slot 개수만 캐시하는 {@link CompiledQuery}의 기본 구현이다.
 * record 의미상 immutable하며, {@link #bind(Object...)}와 {@link #bindList(List)}는 새 {@link SqlStatement}을
 * 만들어 반환하므로 동일 인스턴스를 여러 스레드에서 안전하게 공유할 수 있다.
 */
public record SimpleCompiledQuery(String sql, int parameterCount) implements CompiledQuery {
    public SimpleCompiledQuery {
        Objects.requireNonNull(sql, "sql must not be null");
        if (parameterCount < 0) {
            throw new IllegalArgumentException("parameterCount must not be negative");
        }
    }

    @Override
    public SqlStatement bind(Object... values) {
        Objects.requireNonNull(values, "values must not be null");
        if (values.length != parameterCount) {
            throw new IllegalArgumentException(
                    "Expected " + parameterCount + " binding(s) but received " + values.length);
        }
        return new SqlStatement(sql, List.of(values));
    }

    @Override
    public SqlStatement bindList(List<Object> values) {
        Objects.requireNonNull(values, "values must not be null");
        if (values.size() != parameterCount) {
            throw new IllegalArgumentException(
                    "Expected " + parameterCount + " binding(s) but received " + values.size());
        }
        return new SqlStatement(sql, values);
    }
}
