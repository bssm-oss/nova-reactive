package io.nova.query.criteria;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;

import java.util.List;

/**
 * {@code CriteriaBuilder.in(Expression)}가 반환하는 누적형 IN 술어. {@code cb.in(path).value(a).value(b)}
 * 처럼 값을 이어 붙일 수 있으며, 그 자체가 {@link CriteriaPredicate}(Kind.IN)이므로 술어 변환 경로가
 * 다른 IN 술어와 동일하게 처리한다. {@code value(Expression)}(컬럼 대 컬럼 IN)은 v1 미지원이다.
 *
 * @param <T> IN 대상 컬럼의 값 타입
 */
final class CriteriaInPredicate<T> extends CriteriaPredicate implements CriteriaBuilder.In<T> {

    private final Expression<? extends T> expression;

    CriteriaInPredicate(CriteriaColumnPath path, Expression<? extends T> expression) {
        super(Kind.IN, path, null, null, null, null, new java.util.ArrayList<>(), null, null, false);
        this.expression = expression;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Expression<T> getExpression() {
        return (Expression<T>) expression;
    }

    @Override
    public CriteriaBuilder.In<T> value(T value) {
        inValues().add(value);
        return this;
    }

    @Override
    public CriteriaBuilder.In<T> value(Expression<? extends T> value) {
        throw new CriteriaException("CriteriaBuilder.In.value(Expression) (column-to-column IN) is not supported in v1");
    }

    List<Object> values() {
        return inValues();
    }
}
