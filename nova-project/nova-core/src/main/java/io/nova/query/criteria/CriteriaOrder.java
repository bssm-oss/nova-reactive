package io.nova.query.criteria;

import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Nulls;
import jakarta.persistence.criteria.Order;

/**
 * {@code CriteriaBuilder.asc/desc}가 만드는 정렬 항목. 정렬 대상은 단일 컬럼 경로여야 하며, 엔티티 경로
 * 변환 시 Nova {@code Sort.Order}로, 스칼라 경로에서는 {@code order by "col" asc|desc}로 렌더된다.
 * NULL 정렬 우선순위 지정({@code NULLS FIRST/LAST})은 v1 범위 밖으로 {@code Nulls.NONE}만 노출한다.
 */
final class CriteriaOrder implements Order {

    private final Expression<?> expression;
    private final CriteriaColumnPath path;
    private final boolean ascending;

    CriteriaOrder(Expression<?> expression, CriteriaColumnPath path, boolean ascending) {
        this.expression = expression;
        this.path = path;
        this.ascending = ascending;
    }

    CriteriaColumnPath path() {
        return path;
    }

    @Override
    public Order reverse() {
        return new CriteriaOrder(expression, path, !ascending);
    }

    @Override
    public boolean isAscending() {
        return ascending;
    }

    @Override
    public Nulls getNullPrecedence() {
        return Nulls.NONE;
    }

    @Override
    public Expression<?> getExpression() {
        return expression;
    }
}
