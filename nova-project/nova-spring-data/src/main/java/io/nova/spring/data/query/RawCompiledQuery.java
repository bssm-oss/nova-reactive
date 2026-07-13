package io.nova.spring.data.query;

import io.nova.sql.CompiledQuery;
import io.nova.sql.SqlStatement;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 이미 dialect bind marker로 렌더링된 native SQL과 그 marker 개수를 감싼 {@link CompiledQuery}다.
 * native 엔티티 반환 {@code @Query}가 core의 {@code findAll(Class, CompiledQuery, Object...)} 엔티티
 * 하이드레이션 경로에 그대로 위임하기 위한 어댑터 — 별도 엔티티 매핑 로직을 spring-data 쪽에 복제하지
 * 않는다.
 */
public final class RawCompiledQuery implements CompiledQuery {

    private final String sql;
    private final int parameterCount;

    public RawCompiledQuery(String sql, int parameterCount) {
        this.sql = sql;
        this.parameterCount = parameterCount;
    }

    @Override
    public String sql() {
        return sql;
    }

    @Override
    public int parameterCount() {
        return parameterCount;
    }

    @Override
    public SqlStatement bind(Object... values) {
        // Arrays.asList는 null element를 허용한다(SQL NULL 바인딩). List.of는 거부하므로 쓰지 않는다.
        return bindList(new ArrayList<>(Arrays.asList(values == null ? new Object[0] : values)));
    }

    @Override
    public SqlStatement bindList(List<Object> values) {
        List<Object> safe = values == null ? List.of() : values;
        if (safe.size() != parameterCount) {
            throw new IllegalArgumentException(
                    "Expected " + parameterCount + " bindings but received " + safe.size() + " for: " + sql);
        }
        return new SqlStatement(sql, new ArrayList<>(safe));
    }
}
