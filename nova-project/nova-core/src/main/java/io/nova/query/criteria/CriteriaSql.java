package io.nova.query.criteria;

import java.util.List;

/**
 * Criteria 스칼라/집계 쿼리를 dialect SQL로 렌더한 결과. {@code sql}은 dialect bind marker가 순서대로
 * 박힌 SELECT이고, {@code bindings}는 각 marker에 대응하는 positional 값이다. {@code selectionCount}는
 * 결과 행을 단일 스칼라로 발행할지 {@code Object[]}로 발행할지 결정한다.
 */
record CriteriaSql(String sql, List<Object> bindings, int selectionCount) {
    CriteriaSql {
        bindings = List.copyOf(bindings);
    }
}
