package io.nova.query.criteria;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Subquery;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Nova가 리액티브로 지원하는 Criteria 팩토리 메서드만 구현한 {@code CriteriaBuilder}. 나머지 ~150개
 * 미지원 메서드는 {@link AbstractCriteriaBuilder}가 fail-fast로 거부한다. 지원 범위:
 * <ul>
 *   <li>쿼리 생성: {@code createQuery()}, {@code createQuery(Class)}
 *   <li>논리: {@code and}/{@code or}/{@code not}
 *   <li>비교: {@code equal}/{@code notEqual}, {@code greaterThan(OrEqualTo)}/{@code lessThan(OrEqualTo)},
 *       {@code gt}/{@code ge}/{@code lt}/{@code le}, {@code between}
 *   <li>NULL: {@code isNull}/{@code isNotNull}
 *   <li>패턴: {@code like}/{@code notLike}(리터럴 패턴)
 *   <li>IN: {@code in(Expression)}(누적형)
 *   <li>집계: {@code count}/{@code countDistinct}/{@code sum}/{@code avg}/{@code max}/{@code min}
 *   <li>정렬: {@code asc}/{@code desc}
 * </ul>
 * 비교/집계/정렬의 표현식 인자는 단일 컬럼 경로({@code root.get("attr")} 또는 루트 자체)여야 하며,
 * 컬럼 대 컬럼 비교나 표현식 값은 v1에서 거부된다.
 */
public final class SimpleCriteriaBuilder extends AbstractCriteriaBuilder {

    private final CriteriaMetamodel metamodel;

    public SimpleCriteriaBuilder(CriteriaMetamodel metamodel) {
        this.metamodel = Objects.requireNonNull(metamodel, "metamodel must not be null");
    }

    // --- query creation -------------------------------------------------------------------------

    @Override
    public CriteriaQuery<Object> createQuery() {
        return new CriteriaQueryImpl<>(Object.class, metamodel);
    }

    @Override
    public <T> CriteriaQuery<T> createQuery(Class<T> resultClass) {
        return new CriteriaQueryImpl<>(resultClass, metamodel);
    }

    // --- ordering -------------------------------------------------------------------------------

    @Override
    public Order asc(Expression<?> expression) {
        return new CriteriaOrder(expression, path(expression, "asc"), true);
    }

    @Override
    public Order desc(Expression<?> expression) {
        return new CriteriaOrder(expression, path(expression, "desc"), false);
    }

    // --- aggregates -----------------------------------------------------------------------------

    @Override
    public Expression<Long> count(Expression<?> expression) {
        return new CriteriaAggregate<>(AggregateFunction.COUNT, path(expression, "count"), Long.class);
    }

    @Override
    public Expression<Long> countDistinct(Expression<?> expression) {
        return new CriteriaAggregate<>(AggregateFunction.COUNT_DISTINCT, path(expression, "countDistinct"), Long.class);
    }

    @Override
    public <N extends Number> Expression<Double> avg(Expression<N> expression) {
        return new CriteriaAggregate<>(AggregateFunction.AVG, path(expression, "avg"), Double.class);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <N extends Number> Expression<N> sum(Expression<N> expression) {
        return new CriteriaAggregate<>(AggregateFunction.SUM, path(expression, "sum"), (Class<N>) expression.getJavaType());
    }

    @Override
    @SuppressWarnings("unchecked")
    public <N extends Number> Expression<N> max(Expression<N> expression) {
        return new CriteriaAggregate<>(AggregateFunction.MAX, path(expression, "max"), (Class<N>) expression.getJavaType());
    }

    @Override
    @SuppressWarnings("unchecked")
    public <N extends Number> Expression<N> min(Expression<N> expression) {
        return new CriteriaAggregate<>(AggregateFunction.MIN, path(expression, "min"), (Class<N>) expression.getJavaType());
    }

    // --- logical --------------------------------------------------------------------------------

    @Override
    public Predicate and(Expression<Boolean> left, Expression<Boolean> right) {
        return CriteriaPredicate.junction(CriteriaPredicate.Kind.AND,
                List.of(predicate(left, "and"), predicate(right, "and")));
    }

    @Override
    public Predicate and(Predicate... restrictions) {
        return junction(CriteriaPredicate.Kind.AND, List.of(restrictions), "and");
    }

    @Override
    public Predicate and(List<Predicate> restrictions) {
        return junction(CriteriaPredicate.Kind.AND, restrictions, "and");
    }

    @Override
    public Predicate or(Expression<Boolean> left, Expression<Boolean> right) {
        return CriteriaPredicate.junction(CriteriaPredicate.Kind.OR,
                List.of(predicate(left, "or"), predicate(right, "or")));
    }

    @Override
    public Predicate or(Predicate... restrictions) {
        return junction(CriteriaPredicate.Kind.OR, List.of(restrictions), "or");
    }

    @Override
    public Predicate or(List<Predicate> restrictions) {
        return junction(CriteriaPredicate.Kind.OR, restrictions, "or");
    }

    @Override
    public Predicate not(Expression<Boolean> restriction) {
        return CriteriaPredicate.negate(predicate(restriction, "not"));
    }

    // --- null -----------------------------------------------------------------------------------

    @Override
    public Predicate isNull(Expression<?> expression) {
        return CriteriaPredicate.isNull(path(expression, "isNull"));
    }

    @Override
    public Predicate isNotNull(Expression<?> expression) {
        return CriteriaPredicate.isNotNull(path(expression, "isNotNull"));
    }

    // --- equality -------------------------------------------------------------------------------

    @Override
    public Predicate equal(Expression<?> expression, Object value) {
        CriteriaColumnPath path = path(expression, "equal");
        if (value == null) {
            return CriteriaPredicate.isNull(path);
        }
        CriteriaGuards.rejectExpressionValue(value, "CriteriaBuilder.equal");
        return CriteriaPredicate.comparison(path, CompareOp.EQ, value);
    }

    @Override
    public Predicate notEqual(Expression<?> expression, Object value) {
        CriteriaColumnPath path = path(expression, "notEqual");
        if (value == null) {
            return CriteriaPredicate.isNotNull(path);
        }
        CriteriaGuards.rejectExpressionValue(value, "CriteriaBuilder.notEqual");
        return CriteriaPredicate.comparison(path, CompareOp.NE, value);
    }

    // --- comparable ordering --------------------------------------------------------------------

    @Override
    public <Y extends Comparable<? super Y>> Predicate greaterThan(Expression<? extends Y> expression, Y value) {
        return compare(expression, CompareOp.GT, value, "greaterThan");
    }

    @Override
    public <Y extends Comparable<? super Y>> Predicate greaterThanOrEqualTo(Expression<? extends Y> expression, Y value) {
        return compare(expression, CompareOp.GE, value, "greaterThanOrEqualTo");
    }

    @Override
    public <Y extends Comparable<? super Y>> Predicate lessThan(Expression<? extends Y> expression, Y value) {
        return compare(expression, CompareOp.LT, value, "lessThan");
    }

    @Override
    public <Y extends Comparable<? super Y>> Predicate lessThanOrEqualTo(Expression<? extends Y> expression, Y value) {
        return compare(expression, CompareOp.LE, value, "lessThanOrEqualTo");
    }

    @Override
    public <Y extends Comparable<? super Y>> Predicate between(Expression<? extends Y> expression, Y low, Y high) {
        Objects.requireNonNull(low, "between low must not be null");
        Objects.requireNonNull(high, "between high must not be null");
        return CriteriaPredicate.between(path(expression, "between"), low, high);
    }

    // --- numeric ordering -----------------------------------------------------------------------

    @Override
    public Predicate gt(Expression<? extends Number> expression, Number value) {
        return compare(expression, CompareOp.GT, value, "gt");
    }

    @Override
    public Predicate ge(Expression<? extends Number> expression, Number value) {
        return compare(expression, CompareOp.GE, value, "ge");
    }

    @Override
    public Predicate lt(Expression<? extends Number> expression, Number value) {
        return compare(expression, CompareOp.LT, value, "lt");
    }

    @Override
    public Predicate le(Expression<? extends Number> expression, Number value) {
        return compare(expression, CompareOp.LE, value, "le");
    }

    // --- like / in ------------------------------------------------------------------------------

    @Override
    public Predicate like(Expression<String> expression, String pattern) {
        Objects.requireNonNull(pattern, "like pattern must not be null");
        return CriteriaPredicate.like(path(expression, "like"), pattern, false);
    }

    @Override
    public Predicate notLike(Expression<String> expression, String pattern) {
        Objects.requireNonNull(pattern, "notLike pattern must not be null");
        return CriteriaPredicate.like(path(expression, "notLike"), pattern, true);
    }

    @Override
    public <T> CriteriaBuilder.In<T> in(Expression<? extends T> expression) {
        return new CriteriaInPredicate<>(path(expression, "in"), expression);
    }

    // --- subquery predicates --------------------------------------------------------------------

    @Override
    public Predicate exists(Subquery<?> subquery) {
        return CriteriaPredicate.exists(requireSubquery(subquery, "exists"), false);
    }

    @Override
    public Predicate equal(Expression<?> x, Expression<?> y) {
        return subqueryCompare(x, y, CompareOp.EQ, "equal");
    }

    @Override
    public Predicate notEqual(Expression<?> x, Expression<?> y) {
        return subqueryCompare(x, y, CompareOp.NE, "notEqual");
    }

    @Override
    public <Y extends Comparable<? super Y>> Predicate greaterThan(Expression<? extends Y> x, Expression<? extends Y> y) {
        return subqueryCompare(x, y, CompareOp.GT, "greaterThan");
    }

    @Override
    public <Y extends Comparable<? super Y>> Predicate greaterThanOrEqualTo(Expression<? extends Y> x, Expression<? extends Y> y) {
        return subqueryCompare(x, y, CompareOp.GE, "greaterThanOrEqualTo");
    }

    @Override
    public <Y extends Comparable<? super Y>> Predicate lessThan(Expression<? extends Y> x, Expression<? extends Y> y) {
        return subqueryCompare(x, y, CompareOp.LT, "lessThan");
    }

    @Override
    public <Y extends Comparable<? super Y>> Predicate lessThanOrEqualTo(Expression<? extends Y> x, Expression<? extends Y> y) {
        return subqueryCompare(x, y, CompareOp.LE, "lessThanOrEqualTo");
    }

    @Override
    public Predicate gt(Expression<? extends Number> x, Expression<? extends Number> y) {
        return subqueryCompare(x, y, CompareOp.GT, "gt");
    }

    @Override
    public Predicate ge(Expression<? extends Number> x, Expression<? extends Number> y) {
        return subqueryCompare(x, y, CompareOp.GE, "ge");
    }

    @Override
    public Predicate lt(Expression<? extends Number> x, Expression<? extends Number> y) {
        return subqueryCompare(x, y, CompareOp.LT, "lt");
    }

    @Override
    public Predicate le(Expression<? extends Number> x, Expression<? extends Number> y) {
        return subqueryCompare(x, y, CompareOp.LE, "le");
    }

    private static Predicate subqueryCompare(Expression<?> left, Expression<?> right, CompareOp op, String name) {
        CriteriaColumnPath leftPath = path(left, name);
        if (right instanceof CriteriaSubquery<?> subquery) {
            return CriteriaPredicate.comparisonSubquery(leftPath, op, subquery);
        }
        if (right instanceof CriteriaColumnPath rightPath) {
            // 컬럼 대 컬럼 비교: join/상관 서브쿼리에서만 의미가 있으며 alias 한정 SQL 경로로 렌더된다.
            return CriteriaPredicate.comparisonColumn(leftPath, op, rightPath);
        }
        throw new CriteriaException("CriteriaBuilder." + name
                + " requires a scalar subquery or another attribute path as the right-hand side");
    }

    private static CriteriaSubquery<?> requireSubquery(Subquery<?> subquery, String name) {
        if (subquery instanceof CriteriaSubquery<?> impl) {
            return impl;
        }
        throw new CriteriaException("CriteriaBuilder." + name
                + " requires a Subquery built by this CriteriaBuilder");
    }

    // --- helpers --------------------------------------------------------------------------------

    private Predicate compare(Expression<?> expression, CompareOp op, Object value, String op2) {
        Objects.requireNonNull(value, op2 + " value must not be null");
        CriteriaGuards.rejectExpressionValue(value, "CriteriaBuilder." + op2);
        return CriteriaPredicate.comparison(path(expression, op2), op, value);
    }

    private CriteriaPredicate junction(CriteriaPredicate.Kind kind, List<Predicate> parts, String op) {
        List<CriteriaPredicate> children = new ArrayList<>(parts.size());
        for (Predicate part : parts) {
            children.add(predicate(part, op));
        }
        return CriteriaPredicate.junction(kind, children);
    }

    private static CriteriaColumnPath path(Expression<?> expression, String op) {
        if (expression instanceof CriteriaColumnPath columnPath) {
            return columnPath;
        }
        throw new CriteriaException("CriteriaBuilder." + op
                + " requires a single-column attribute path (root or root.get(\"attr\")) in v1, got "
                + (expression == null ? "null" : expression.getClass().getSimpleName()));
    }

    private static CriteriaPredicate predicate(Expression<Boolean> expression, String op) {
        if (expression instanceof CriteriaPredicate predicate) {
            return predicate;
        }
        throw new CriteriaException("CriteriaBuilder." + op
                + " requires Predicate operands built by this CriteriaBuilder");
    }
}
