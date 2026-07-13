package io.nova.query.criteria;

import jakarta.persistence.criteria.Expression;

/**
 * predicate 조립 시 우변 값에 대한 공통 fail-fast 가드. {@link SimpleCriteriaBuilder}의 {@code cb.*}
 * 팩토리와 {@link AbstractCriteriaExpression}의 path-level 편의 메서드({@code equalTo}/{@code in} 등)가
 * 동일한 의미를 갖도록 한 곳에 모은다 — Expression 우변(컬럼 대 컬럼)과 IN의 null 원소는 조용히 잘못된
 * SQL을 만들지 않고 여기서 거부한다.
 */
final class CriteriaGuards {

    private CriteriaGuards() {
    }

    /**
     * 비교/술어의 우변 값이 {@link Expression}(컬럼 대 컬럼 비교)이면 거부한다. {@code op}는 에러 메시지에
     * 표시할 연산 이름(예: {@code "CriteriaBuilder.equal"}, {@code "Expression.equalTo"})이다.
     */
    static void rejectExpressionValue(Object value, String op) {
        if (value instanceof Expression) {
            throw new CriteriaException(op + " with an Expression right-hand side "
                    + "(column-to-column comparison) is not supported in v1");
        }
    }

    /** IN 원소가 {@code null} 또는 {@link Expression}이면 거부한다(SQL {@code IN (..., NULL, ...)} silent 오류 회피). */
    static void rejectInElement(Object element, String op) {
        if (element == null) {
            throw new CriteriaException(op + " does not accept a null element; use isNull() for NULL matching");
        }
        rejectExpressionValue(element, op);
    }
}
