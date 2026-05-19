package io.nova.sql;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * SQL 문자열과 positional binding 묶음. {@code bindings}는 canonical constructor에서
 * {@code unmodifiableList(new ArrayList<>(...))}로 방어 카피되어 immutable view로 노출된다.
 * 개별 element는 SQL NULL과 매핑되도록 {@code null}을 허용한다 — 예를 들어 {@code @Embedded}
 * 필드가 {@code null}이면 펼쳐진 sub-column 모두에 {@code null}이 바인딩된다.
 */
public record SqlStatement(String sql, List<Object> bindings) {
    public SqlStatement {
        // List.copyOf는 null element를 거부하므로 NativeQuery와 동일한 방식으로 방어 카피해
        // 개별 null binding(SQL NULL)을 허용한다.
        bindings = Collections.unmodifiableList(new ArrayList<>(bindings));
    }
}
