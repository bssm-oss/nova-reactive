package io.nova.query.jpql;

import java.util.List;

/**
 * JPQL AST를 특정 dialect SQL로 변환한 결과. {@code sql}은 dialect bind marker가 순서대로 박힌 SQL이고,
 * {@code bindings}는 각 marker에 대응하는 {@link JpqlBinding}(리터럴 또는 파라미터 참조)이다.
 * {@code resultKind}는 실행 계층이 결과를 엔티티/스칼라/영향행수 중 무엇으로 다룰지 알려준다.
 * <p>
 * {@code slots}는 스칼라 SELECT의 논리 결과 슬롯 목록이다({@code SELECT} 항목 개수만큼, {@code selectionCount}는
 * 물리 컬럼 개수). 대부분 슬롯 1개=물리 컬럼 1개지만, 복합키 타겟 to-one 투영({@code SELECT c.parent})은
 * 여러 물리 컬럼을 논리 슬롯 1개(id-stub)로 묶는다. 벌크 UPDATE/DELETE와 {@code SELECT NEW} 인자는 이 필드를
 * 쓰지 않는다({@code SELECT NEW}는 1:1 컬럼-생성자인자 매핑을 그대로 유지한다).
 */
public record TranslatedSql(
        String sql, List<JpqlBinding> bindings, ResultKind resultKind, int selectionCount,
        List<ResultSlot> slots) {

    /** 편의 생성자: {@code slots} 없이 만들면 빈 목록으로 위임한다(벌크 UPDATE/DELETE 등 기존 호출부). */
    public TranslatedSql(String sql, List<JpqlBinding> bindings, ResultKind resultKind, int selectionCount) {
        this(sql, bindings, resultKind, selectionCount, List.of());
    }

    public TranslatedSql {
        bindings = List.copyOf(bindings);
        slots = List.copyOf(slots);
    }

    public enum ResultKind {
        /** 스칼라/집계/투영 SELECT — 각 행을 {@code Object[]}(또는 단일 스칼라)로 매핑한다. */
        SCALAR,
        /** 벌크 UPDATE/DELETE — 영향 행 수를 반환한다. */
        MUTATION
    }

    /**
     * 스칼라 SELECT 결과의 논리 슬롯 하나. 물리 컬럼 {@code [firstColumn, firstColumn + columnCount)}에 대응한다.
     * {@code compositeFk}가 {@code null}이면 평범한 단일 컬럼 스칼라 슬롯({@code columnCount == 1})이고,
     * non-null이면 복합키 타겟 to-one 투영이다({@code columnCount} == 참조 엔티티 {@code @Id} 컴포넌트 개수).
     */
    public record ResultSlot(int firstColumn, int columnCount, io.nova.metadata.ToOneForeignKey compositeFk) {
    }
}
