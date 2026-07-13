package io.nova.query.criteria;

import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Selection;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

/**
 * {@code jakarta.persistence.criteria.Expression}의 공통 골격. 서브클래스({@link CriteriaPath},
 * {@link CriteriaAggregate})가 실제 SQL/QuerySpec 변환에 필요한 구조 데이터를 담고, 이 base는 JPA
 * {@code Expression}/{@code Selection}/{@code TupleElement} 계약의 나머지 메서드를 fail-fast 또는
 * 최소 지원으로 채운다. 지원하지 않는 fluent 구성(예: {@code cast}, {@code equalTo(Expression)})은
 * {@link CriteriaException}으로 거부한다 — predicate 조립은 {@link SimpleCriteriaBuilder}에 집중된다.
 *
 * @param <T> 표현식의 Java 결과 타입
 */
abstract class AbstractCriteriaExpression<T> implements Expression<T> {

    private final Class<T> javaType;
    private String alias;

    AbstractCriteriaExpression(Class<T> javaType) {
        this.javaType = javaType;
    }

    // --- TupleElement -------------------------------------------------------------------------

    @Override
    public Class<? extends T> getJavaType() {
        return javaType;
    }

    @Override
    public String getAlias() {
        return alias;
    }

    // --- Selection ----------------------------------------------------------------------------

    @Override
    public Selection<T> alias(String name) {
        this.alias = name;
        return this;
    }

    @Override
    public boolean isCompoundSelection() {
        return false;
    }

    @Override
    public List<Selection<?>> getCompoundSelectionItems() {
        throw new CriteriaException("Expression is not a compound selection");
    }

    // --- Expression convenience (leaf-path only) ----------------------------------------------

    @Override
    public Predicate isNull() {
        return CriteriaPredicate.isNull(requirePath("isNull"));
    }

    @Override
    public Predicate isNotNull() {
        return CriteriaPredicate.isNotNull(requirePath("isNotNull"));
    }

    @Override
    public Predicate equalTo(Expression<?> value) {
        throw unsupported("equalTo(Expression)");
    }

    @Override
    public Predicate equalTo(Object value) {
        CriteriaPath path = requirePath("equalTo");
        if (value == null) {
            return CriteriaPredicate.isNull(path);
        }
        CriteriaGuards.rejectExpressionValue(value, "Expression.equalTo");
        return CriteriaPredicate.comparison(path, CompareOp.EQ, value);
    }

    @Override
    public Predicate notEqualTo(Expression<?> value) {
        throw unsupported("notEqualTo(Expression)");
    }

    @Override
    public Predicate notEqualTo(Object value) {
        CriteriaPath path = requirePath("notEqualTo");
        if (value == null) {
            return CriteriaPredicate.isNotNull(path);
        }
        CriteriaGuards.rejectExpressionValue(value, "Expression.notEqualTo");
        return CriteriaPredicate.comparison(path, CompareOp.NE, value);
    }

    @Override
    public Predicate in(Object... values) {
        return inPredicate(requirePath("in"), Arrays.asList(values));
    }

    @Override
    public Predicate in(Expression<?>... values) {
        throw unsupported("in(Expression...)");
    }

    @Override
    public Predicate in(Collection<?> values) {
        return inPredicate(requirePath("in"), new ArrayList<>(values));
    }

    private static Predicate inPredicate(CriteriaPath path, List<?> values) {
        // null/Expression 원소를 먼저 거부하므로(List.of/copyOf가 아닌 raw 순회) silent 잘못된 IN을 막는다.
        for (Object value : values) {
            CriteriaGuards.rejectInElement(value, "Expression.in");
        }
        return CriteriaPredicate.in(path, List.copyOf(values), false);
    }

    @Override
    public Predicate in(Expression<Collection<?>> values) {
        throw unsupported("in(Expression<Collection>)");
    }

    @Override
    @SuppressWarnings("unchecked")
    public <X> Expression<X> as(Class<X> type) {
        // Nova는 별도 SQL CAST 없이 표현식을 재타이핑만 한다(지원 연산은 타입에 무관하게 렌더된다).
        return (Expression<X>) this;
    }

    @Override
    public <X> Expression<X> cast(Class<X> type) {
        throw unsupported("cast(Class) with an explicit SQL CAST");
    }

    // --- helpers ------------------------------------------------------------------------------

    private CriteriaPath requirePath(String op) {
        if (this instanceof CriteriaPath path) {
            return path;
        }
        throw new CriteriaException("Expression." + op
                + " is only supported on an attribute path in v1; use CriteriaBuilder." + op + " instead");
    }

    static CriteriaException unsupported(String what) {
        return new CriteriaException("Criteria construct '" + what + "' is not supported in v1");
    }
}
