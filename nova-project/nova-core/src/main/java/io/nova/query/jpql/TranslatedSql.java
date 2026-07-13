package io.nova.query.jpql;

import java.util.List;

/**
 * JPQL AST를 특정 dialect SQL로 변환한 결과. {@code sql}은 dialect bind marker가 순서대로 박힌 SQL이고,
 * {@code bindings}는 각 marker에 대응하는 {@link JpqlBinding}(리터럴 또는 파라미터 참조)이다.
 * {@code resultKind}는 실행 계층이 결과를 엔티티/스칼라/영향행수 중 무엇으로 다룰지 알려준다.
 */
public record TranslatedSql(String sql, List<JpqlBinding> bindings, ResultKind resultKind, int selectionCount) {
    public TranslatedSql {
        bindings = List.copyOf(bindings);
    }

    public enum ResultKind {
        /** 스칼라/집계/투영 SELECT — 각 행을 {@code Object[]}(또는 단일 스칼라)로 매핑한다. */
        SCALAR,
        /** 벌크 UPDATE/DELETE — 영향 행 수를 반환한다. */
        MUTATION
    }
}
