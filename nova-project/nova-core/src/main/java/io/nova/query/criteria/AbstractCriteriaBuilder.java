package io.nova.query.criteria;

import jakarta.persistence.criteria.CriteriaBuilder;

/**
 * {@code jakarta.persistence.criteria.CriteriaBuilder}의 완전 fail-fast 기반 구현. JPA CriteriaBuilder는
 * ~180개의 팩토리 메서드를 갖지만 v1이 리액티브로 지원하는 것은 그 부분집합(비교/논리/집계/정렬/LIKE/
 * BETWEEN/IN/NULL 등)뿐이다. 이 base는 <em>모든</em> 메서드를 {@link CriteriaException}으로 거부해
 * 미지원 구성이 조용히 통과하지 않도록 하고, {@link SimpleCriteriaBuilder}가 지원 대상만 override한다.
 * 이렇게 방대한 stub 목록을 한 곳에 격리해 지원 표면과 미지원 표면을 명확히 분리한다.
 */
public abstract class AbstractCriteriaBuilder implements CriteriaBuilder {

    /** 미지원 Criteria 팩토리 호출을 일관된 메시지로 거부한다. */
    protected static CriteriaException unsupported(String method) {
        return new CriteriaException("CriteriaBuilder." + method
                + " is not supported by Nova's reactive Criteria in v1");
    }

    @Override
    public jakarta.persistence.criteria.CriteriaQuery<java.lang.Object> createQuery() {
        throw unsupported("createQuery");
    }

    @Override
    public <T> jakarta.persistence.criteria.CriteriaQuery<T> createQuery(java.lang.Class<T> a0) {
        throw unsupported("createQuery");
    }

    @Override
    public jakarta.persistence.criteria.CriteriaQuery<jakarta.persistence.Tuple> createTupleQuery() {
        throw unsupported("createTupleQuery");
    }

    @Override
    public <T> jakarta.persistence.criteria.CriteriaUpdate<T> createCriteriaUpdate(java.lang.Class<T> a0) {
        throw unsupported("createCriteriaUpdate");
    }

    @Override
    public <T> jakarta.persistence.criteria.CriteriaDelete<T> createCriteriaDelete(java.lang.Class<T> a0) {
        throw unsupported("createCriteriaDelete");
    }

    @Override
    public <Y> jakarta.persistence.criteria.CompoundSelection<Y> construct(java.lang.Class<Y> a0, jakarta.persistence.criteria.Selection<?>... a1) {
        throw unsupported("construct");
    }

    @Override
    public jakarta.persistence.criteria.CompoundSelection<jakarta.persistence.Tuple> tuple(jakarta.persistence.criteria.Selection<?>... a0) {
        throw unsupported("tuple");
    }

    @Override
    public jakarta.persistence.criteria.CompoundSelection<jakarta.persistence.Tuple> tuple(java.util.List<jakarta.persistence.criteria.Selection<?>> a0) {
        throw unsupported("tuple");
    }

    @Override
    public jakarta.persistence.criteria.CompoundSelection<java.lang.Object[]> array(jakarta.persistence.criteria.Selection<?>... a0) {
        throw unsupported("array");
    }

    @Override
    public jakarta.persistence.criteria.CompoundSelection<java.lang.Object[]> array(java.util.List<jakarta.persistence.criteria.Selection<?>> a0) {
        throw unsupported("array");
    }

    @Override
    public jakarta.persistence.criteria.Order asc(jakarta.persistence.criteria.Expression<?> a0) {
        throw unsupported("asc");
    }

    @Override
    public jakarta.persistence.criteria.Order desc(jakarta.persistence.criteria.Expression<?> a0) {
        throw unsupported("desc");
    }

    @Override
    public jakarta.persistence.criteria.Order asc(jakarta.persistence.criteria.Expression<?> a0, jakarta.persistence.criteria.Nulls a1) {
        throw unsupported("asc");
    }

    @Override
    public jakarta.persistence.criteria.Order desc(jakarta.persistence.criteria.Expression<?> a0, jakarta.persistence.criteria.Nulls a1) {
        throw unsupported("desc");
    }

    @Override
    public <N extends java.lang.Number> jakarta.persistence.criteria.Expression<java.lang.Double> avg(jakarta.persistence.criteria.Expression<N> a0) {
        throw unsupported("avg");
    }

    @Override
    public <N extends java.lang.Number> jakarta.persistence.criteria.Expression<N> sum(jakarta.persistence.criteria.Expression<N> a0) {
        throw unsupported("sum");
    }

    @Override
    public jakarta.persistence.criteria.Expression<java.lang.Long> sumAsLong(jakarta.persistence.criteria.Expression<java.lang.Integer> a0) {
        throw unsupported("sumAsLong");
    }

    @Override
    public jakarta.persistence.criteria.Expression<java.lang.Double> sumAsDouble(jakarta.persistence.criteria.Expression<java.lang.Float> a0) {
        throw unsupported("sumAsDouble");
    }

    @Override
    public <N extends java.lang.Number> jakarta.persistence.criteria.Expression<N> max(jakarta.persistence.criteria.Expression<N> a0) {
        throw unsupported("max");
    }

    @Override
    public <N extends java.lang.Number> jakarta.persistence.criteria.Expression<N> min(jakarta.persistence.criteria.Expression<N> a0) {
        throw unsupported("min");
    }

    @Override
    public <X extends java.lang.Comparable<? super X>> jakarta.persistence.criteria.Expression<X> greatest(jakarta.persistence.criteria.Expression<X> a0) {
        throw unsupported("greatest");
    }

    @Override
    public <X extends java.lang.Comparable<? super X>> jakarta.persistence.criteria.Expression<X> least(jakarta.persistence.criteria.Expression<X> a0) {
        throw unsupported("least");
    }

    @Override
    public jakarta.persistence.criteria.Expression<java.lang.Long> count(jakarta.persistence.criteria.Expression<?> a0) {
        throw unsupported("count");
    }

    @Override
    public jakarta.persistence.criteria.Expression<java.lang.Long> countDistinct(jakarta.persistence.criteria.Expression<?> a0) {
        throw unsupported("countDistinct");
    }

    @Override
    public jakarta.persistence.criteria.Predicate exists(jakarta.persistence.criteria.Subquery<?> a0) {
        throw unsupported("exists");
    }

    @Override
    public <Y> jakarta.persistence.criteria.Expression<Y> all(jakarta.persistence.criteria.Subquery<Y> a0) {
        throw unsupported("all");
    }

    @Override
    public <Y> jakarta.persistence.criteria.Expression<Y> some(jakarta.persistence.criteria.Subquery<Y> a0) {
        throw unsupported("some");
    }

    @Override
    public <Y> jakarta.persistence.criteria.Expression<Y> any(jakarta.persistence.criteria.Subquery<Y> a0) {
        throw unsupported("any");
    }

    @Override
    public jakarta.persistence.criteria.Predicate and(jakarta.persistence.criteria.Expression<java.lang.Boolean> a0, jakarta.persistence.criteria.Expression<java.lang.Boolean> a1) {
        throw unsupported("and");
    }

    @Override
    public jakarta.persistence.criteria.Predicate and(jakarta.persistence.criteria.Predicate... a0) {
        throw unsupported("and");
    }

    @Override
    public jakarta.persistence.criteria.Predicate and(java.util.List<jakarta.persistence.criteria.Predicate> a0) {
        throw unsupported("and");
    }

    @Override
    public jakarta.persistence.criteria.Predicate or(jakarta.persistence.criteria.Expression<java.lang.Boolean> a0, jakarta.persistence.criteria.Expression<java.lang.Boolean> a1) {
        throw unsupported("or");
    }

    @Override
    public jakarta.persistence.criteria.Predicate or(jakarta.persistence.criteria.Predicate... a0) {
        throw unsupported("or");
    }

    @Override
    public jakarta.persistence.criteria.Predicate or(java.util.List<jakarta.persistence.criteria.Predicate> a0) {
        throw unsupported("or");
    }

    @Override
    public jakarta.persistence.criteria.Predicate not(jakarta.persistence.criteria.Expression<java.lang.Boolean> a0) {
        throw unsupported("not");
    }

    @Override
    public jakarta.persistence.criteria.Predicate conjunction() {
        throw unsupported("conjunction");
    }

    @Override
    public jakarta.persistence.criteria.Predicate disjunction() {
        throw unsupported("disjunction");
    }

    @Override
    public jakarta.persistence.criteria.Predicate isTrue(jakarta.persistence.criteria.Expression<java.lang.Boolean> a0) {
        throw unsupported("isTrue");
    }

    @Override
    public jakarta.persistence.criteria.Predicate isFalse(jakarta.persistence.criteria.Expression<java.lang.Boolean> a0) {
        throw unsupported("isFalse");
    }

    @Override
    public jakarta.persistence.criteria.Predicate isNull(jakarta.persistence.criteria.Expression<?> a0) {
        throw unsupported("isNull");
    }

    @Override
    public jakarta.persistence.criteria.Predicate isNotNull(jakarta.persistence.criteria.Expression<?> a0) {
        throw unsupported("isNotNull");
    }

    @Override
    public jakarta.persistence.criteria.Predicate equal(jakarta.persistence.criteria.Expression<?> a0, jakarta.persistence.criteria.Expression<?> a1) {
        throw unsupported("equal");
    }

    @Override
    public jakarta.persistence.criteria.Predicate equal(jakarta.persistence.criteria.Expression<?> a0, java.lang.Object a1) {
        throw unsupported("equal");
    }

    @Override
    public jakarta.persistence.criteria.Predicate notEqual(jakarta.persistence.criteria.Expression<?> a0, jakarta.persistence.criteria.Expression<?> a1) {
        throw unsupported("notEqual");
    }

    @Override
    public jakarta.persistence.criteria.Predicate notEqual(jakarta.persistence.criteria.Expression<?> a0, java.lang.Object a1) {
        throw unsupported("notEqual");
    }

    @Override
    public <Y extends java.lang.Comparable<? super Y>> jakarta.persistence.criteria.Predicate greaterThan(jakarta.persistence.criteria.Expression<? extends Y> a0, jakarta.persistence.criteria.Expression<? extends Y> a1) {
        throw unsupported("greaterThan");
    }

    @Override
    public <Y extends java.lang.Comparable<? super Y>> jakarta.persistence.criteria.Predicate greaterThan(jakarta.persistence.criteria.Expression<? extends Y> a0, Y a1) {
        throw unsupported("greaterThan");
    }

    @Override
    public <Y extends java.lang.Comparable<? super Y>> jakarta.persistence.criteria.Predicate greaterThanOrEqualTo(jakarta.persistence.criteria.Expression<? extends Y> a0, jakarta.persistence.criteria.Expression<? extends Y> a1) {
        throw unsupported("greaterThanOrEqualTo");
    }

    @Override
    public <Y extends java.lang.Comparable<? super Y>> jakarta.persistence.criteria.Predicate greaterThanOrEqualTo(jakarta.persistence.criteria.Expression<? extends Y> a0, Y a1) {
        throw unsupported("greaterThanOrEqualTo");
    }

    @Override
    public <Y extends java.lang.Comparable<? super Y>> jakarta.persistence.criteria.Predicate lessThan(jakarta.persistence.criteria.Expression<? extends Y> a0, jakarta.persistence.criteria.Expression<? extends Y> a1) {
        throw unsupported("lessThan");
    }

    @Override
    public <Y extends java.lang.Comparable<? super Y>> jakarta.persistence.criteria.Predicate lessThan(jakarta.persistence.criteria.Expression<? extends Y> a0, Y a1) {
        throw unsupported("lessThan");
    }

    @Override
    public <Y extends java.lang.Comparable<? super Y>> jakarta.persistence.criteria.Predicate lessThanOrEqualTo(jakarta.persistence.criteria.Expression<? extends Y> a0, jakarta.persistence.criteria.Expression<? extends Y> a1) {
        throw unsupported("lessThanOrEqualTo");
    }

    @Override
    public <Y extends java.lang.Comparable<? super Y>> jakarta.persistence.criteria.Predicate lessThanOrEqualTo(jakarta.persistence.criteria.Expression<? extends Y> a0, Y a1) {
        throw unsupported("lessThanOrEqualTo");
    }

    @Override
    public <Y extends java.lang.Comparable<? super Y>> jakarta.persistence.criteria.Predicate between(jakarta.persistence.criteria.Expression<? extends Y> a0, jakarta.persistence.criteria.Expression<? extends Y> a1, jakarta.persistence.criteria.Expression<? extends Y> a2) {
        throw unsupported("between");
    }

    @Override
    public <Y extends java.lang.Comparable<? super Y>> jakarta.persistence.criteria.Predicate between(jakarta.persistence.criteria.Expression<? extends Y> a0, Y a1, Y a2) {
        throw unsupported("between");
    }

    @Override
    public jakarta.persistence.criteria.Predicate gt(jakarta.persistence.criteria.Expression<? extends java.lang.Number> a0, jakarta.persistence.criteria.Expression<? extends java.lang.Number> a1) {
        throw unsupported("gt");
    }

    @Override
    public jakarta.persistence.criteria.Predicate gt(jakarta.persistence.criteria.Expression<? extends java.lang.Number> a0, java.lang.Number a1) {
        throw unsupported("gt");
    }

    @Override
    public jakarta.persistence.criteria.Predicate ge(jakarta.persistence.criteria.Expression<? extends java.lang.Number> a0, jakarta.persistence.criteria.Expression<? extends java.lang.Number> a1) {
        throw unsupported("ge");
    }

    @Override
    public jakarta.persistence.criteria.Predicate ge(jakarta.persistence.criteria.Expression<? extends java.lang.Number> a0, java.lang.Number a1) {
        throw unsupported("ge");
    }

    @Override
    public jakarta.persistence.criteria.Predicate lt(jakarta.persistence.criteria.Expression<? extends java.lang.Number> a0, jakarta.persistence.criteria.Expression<? extends java.lang.Number> a1) {
        throw unsupported("lt");
    }

    @Override
    public jakarta.persistence.criteria.Predicate lt(jakarta.persistence.criteria.Expression<? extends java.lang.Number> a0, java.lang.Number a1) {
        throw unsupported("lt");
    }

    @Override
    public jakarta.persistence.criteria.Predicate le(jakarta.persistence.criteria.Expression<? extends java.lang.Number> a0, jakarta.persistence.criteria.Expression<? extends java.lang.Number> a1) {
        throw unsupported("le");
    }

    @Override
    public jakarta.persistence.criteria.Predicate le(jakarta.persistence.criteria.Expression<? extends java.lang.Number> a0, java.lang.Number a1) {
        throw unsupported("le");
    }

    @Override
    public jakarta.persistence.criteria.Expression<java.lang.Integer> sign(jakarta.persistence.criteria.Expression<? extends java.lang.Number> a0) {
        throw unsupported("sign");
    }

    @Override
    public <N extends java.lang.Number> jakarta.persistence.criteria.Expression<N> neg(jakarta.persistence.criteria.Expression<N> a0) {
        throw unsupported("neg");
    }

    @Override
    public <N extends java.lang.Number> jakarta.persistence.criteria.Expression<N> abs(jakarta.persistence.criteria.Expression<N> a0) {
        throw unsupported("abs");
    }

    @Override
    public <N extends java.lang.Number> jakarta.persistence.criteria.Expression<N> ceiling(jakarta.persistence.criteria.Expression<N> a0) {
        throw unsupported("ceiling");
    }

    @Override
    public <N extends java.lang.Number> jakarta.persistence.criteria.Expression<N> floor(jakarta.persistence.criteria.Expression<N> a0) {
        throw unsupported("floor");
    }

    @Override
    public <N extends java.lang.Number> jakarta.persistence.criteria.Expression<N> sum(jakarta.persistence.criteria.Expression<? extends N> a0, jakarta.persistence.criteria.Expression<? extends N> a1) {
        throw unsupported("sum");
    }

    @Override
    public <N extends java.lang.Number> jakarta.persistence.criteria.Expression<N> sum(jakarta.persistence.criteria.Expression<? extends N> a0, N a1) {
        throw unsupported("sum");
    }

    @Override
    public <N extends java.lang.Number> jakarta.persistence.criteria.Expression<N> sum(N a0, jakarta.persistence.criteria.Expression<? extends N> a1) {
        throw unsupported("sum");
    }

    @Override
    public <N extends java.lang.Number> jakarta.persistence.criteria.Expression<N> prod(jakarta.persistence.criteria.Expression<? extends N> a0, jakarta.persistence.criteria.Expression<? extends N> a1) {
        throw unsupported("prod");
    }

    @Override
    public <N extends java.lang.Number> jakarta.persistence.criteria.Expression<N> prod(jakarta.persistence.criteria.Expression<? extends N> a0, N a1) {
        throw unsupported("prod");
    }

    @Override
    public <N extends java.lang.Number> jakarta.persistence.criteria.Expression<N> prod(N a0, jakarta.persistence.criteria.Expression<? extends N> a1) {
        throw unsupported("prod");
    }

    @Override
    public <N extends java.lang.Number> jakarta.persistence.criteria.Expression<N> diff(jakarta.persistence.criteria.Expression<? extends N> a0, jakarta.persistence.criteria.Expression<? extends N> a1) {
        throw unsupported("diff");
    }

    @Override
    public <N extends java.lang.Number> jakarta.persistence.criteria.Expression<N> diff(jakarta.persistence.criteria.Expression<? extends N> a0, N a1) {
        throw unsupported("diff");
    }

    @Override
    public <N extends java.lang.Number> jakarta.persistence.criteria.Expression<N> diff(N a0, jakarta.persistence.criteria.Expression<? extends N> a1) {
        throw unsupported("diff");
    }

    @Override
    public jakarta.persistence.criteria.Expression<java.lang.Number> quot(jakarta.persistence.criteria.Expression<? extends java.lang.Number> a0, jakarta.persistence.criteria.Expression<? extends java.lang.Number> a1) {
        throw unsupported("quot");
    }

    @Override
    public jakarta.persistence.criteria.Expression<java.lang.Number> quot(jakarta.persistence.criteria.Expression<? extends java.lang.Number> a0, java.lang.Number a1) {
        throw unsupported("quot");
    }

    @Override
    public jakarta.persistence.criteria.Expression<java.lang.Number> quot(java.lang.Number a0, jakarta.persistence.criteria.Expression<? extends java.lang.Number> a1) {
        throw unsupported("quot");
    }

    @Override
    public jakarta.persistence.criteria.Expression<java.lang.Integer> mod(jakarta.persistence.criteria.Expression<java.lang.Integer> a0, jakarta.persistence.criteria.Expression<java.lang.Integer> a1) {
        throw unsupported("mod");
    }

    @Override
    public jakarta.persistence.criteria.Expression<java.lang.Integer> mod(jakarta.persistence.criteria.Expression<java.lang.Integer> a0, java.lang.Integer a1) {
        throw unsupported("mod");
    }

    @Override
    public jakarta.persistence.criteria.Expression<java.lang.Integer> mod(java.lang.Integer a0, jakarta.persistence.criteria.Expression<java.lang.Integer> a1) {
        throw unsupported("mod");
    }

    @Override
    public jakarta.persistence.criteria.Expression<java.lang.Double> sqrt(jakarta.persistence.criteria.Expression<? extends java.lang.Number> a0) {
        throw unsupported("sqrt");
    }

    @Override
    public jakarta.persistence.criteria.Expression<java.lang.Double> exp(jakarta.persistence.criteria.Expression<? extends java.lang.Number> a0) {
        throw unsupported("exp");
    }

    @Override
    public jakarta.persistence.criteria.Expression<java.lang.Double> ln(jakarta.persistence.criteria.Expression<? extends java.lang.Number> a0) {
        throw unsupported("ln");
    }

    @Override
    public jakarta.persistence.criteria.Expression<java.lang.Double> power(jakarta.persistence.criteria.Expression<? extends java.lang.Number> a0, jakarta.persistence.criteria.Expression<? extends java.lang.Number> a1) {
        throw unsupported("power");
    }

    @Override
    public jakarta.persistence.criteria.Expression<java.lang.Double> power(jakarta.persistence.criteria.Expression<? extends java.lang.Number> a0, java.lang.Number a1) {
        throw unsupported("power");
    }

    @Override
    public <T extends java.lang.Number> jakarta.persistence.criteria.Expression<T> round(jakarta.persistence.criteria.Expression<T> a0, java.lang.Integer a1) {
        throw unsupported("round");
    }

    @Override
    public jakarta.persistence.criteria.Expression<java.lang.Long> toLong(jakarta.persistence.criteria.Expression<? extends java.lang.Number> a0) {
        throw unsupported("toLong");
    }

    @Override
    public jakarta.persistence.criteria.Expression<java.lang.Integer> toInteger(jakarta.persistence.criteria.Expression<? extends java.lang.Number> a0) {
        throw unsupported("toInteger");
    }

    @Override
    public jakarta.persistence.criteria.Expression<java.lang.Float> toFloat(jakarta.persistence.criteria.Expression<? extends java.lang.Number> a0) {
        throw unsupported("toFloat");
    }

    @Override
    public jakarta.persistence.criteria.Expression<java.lang.Double> toDouble(jakarta.persistence.criteria.Expression<? extends java.lang.Number> a0) {
        throw unsupported("toDouble");
    }

    @Override
    public jakarta.persistence.criteria.Expression<java.math.BigDecimal> toBigDecimal(jakarta.persistence.criteria.Expression<? extends java.lang.Number> a0) {
        throw unsupported("toBigDecimal");
    }

    @Override
    public jakarta.persistence.criteria.Expression<java.math.BigInteger> toBigInteger(jakarta.persistence.criteria.Expression<? extends java.lang.Number> a0) {
        throw unsupported("toBigInteger");
    }

    @Override
    public jakarta.persistence.criteria.Expression<java.lang.String> toString(jakarta.persistence.criteria.Expression<java.lang.Character> a0) {
        throw unsupported("toString");
    }

    @Override
    public <T> jakarta.persistence.criteria.Expression<T> literal(T a0) {
        throw unsupported("literal");
    }

    @Override
    public <T> jakarta.persistence.criteria.Expression<T> nullLiteral(java.lang.Class<T> a0) {
        throw unsupported("nullLiteral");
    }

    @Override
    public <T> jakarta.persistence.criteria.ParameterExpression<T> parameter(java.lang.Class<T> a0) {
        throw unsupported("parameter");
    }

    @Override
    public <T> jakarta.persistence.criteria.ParameterExpression<T> parameter(java.lang.Class<T> a0, java.lang.String a1) {
        throw unsupported("parameter");
    }

    @Override
    public <C extends java.util.Collection<?>> jakarta.persistence.criteria.Predicate isEmpty(jakarta.persistence.criteria.Expression<C> a0) {
        throw unsupported("isEmpty");
    }

    @Override
    public <C extends java.util.Collection<?>> jakarta.persistence.criteria.Predicate isNotEmpty(jakarta.persistence.criteria.Expression<C> a0) {
        throw unsupported("isNotEmpty");
    }

    @Override
    public <C extends java.util.Collection<?>> jakarta.persistence.criteria.Expression<java.lang.Integer> size(jakarta.persistence.criteria.Expression<C> a0) {
        throw unsupported("size");
    }

    @Override
    public <C extends java.util.Collection<?>> jakarta.persistence.criteria.Expression<java.lang.Integer> size(C a0) {
        throw unsupported("size");
    }

    @Override
    public <E, C extends java.util.Collection<E>> jakarta.persistence.criteria.Predicate isMember(jakarta.persistence.criteria.Expression<E> a0, jakarta.persistence.criteria.Expression<C> a1) {
        throw unsupported("isMember");
    }

    @Override
    public <E, C extends java.util.Collection<E>> jakarta.persistence.criteria.Predicate isMember(E a0, jakarta.persistence.criteria.Expression<C> a1) {
        throw unsupported("isMember");
    }

    @Override
    public <E, C extends java.util.Collection<E>> jakarta.persistence.criteria.Predicate isNotMember(jakarta.persistence.criteria.Expression<E> a0, jakarta.persistence.criteria.Expression<C> a1) {
        throw unsupported("isNotMember");
    }

    @Override
    public <E, C extends java.util.Collection<E>> jakarta.persistence.criteria.Predicate isNotMember(E a0, jakarta.persistence.criteria.Expression<C> a1) {
        throw unsupported("isNotMember");
    }

    @Override
    public <V, M extends java.util.Map<?, V>> jakarta.persistence.criteria.Expression<java.util.Collection<V>> values(M a0) {
        throw unsupported("values");
    }

    @Override
    public <K, M extends java.util.Map<K, ?>> jakarta.persistence.criteria.Expression<java.util.Set<K>> keys(M a0) {
        throw unsupported("keys");
    }

    @Override
    public jakarta.persistence.criteria.Predicate like(jakarta.persistence.criteria.Expression<java.lang.String> a0, jakarta.persistence.criteria.Expression<java.lang.String> a1) {
        throw unsupported("like");
    }

    @Override
    public jakarta.persistence.criteria.Predicate like(jakarta.persistence.criteria.Expression<java.lang.String> a0, java.lang.String a1) {
        throw unsupported("like");
    }

    @Override
    public jakarta.persistence.criteria.Predicate like(jakarta.persistence.criteria.Expression<java.lang.String> a0, jakarta.persistence.criteria.Expression<java.lang.String> a1, jakarta.persistence.criteria.Expression<java.lang.Character> a2) {
        throw unsupported("like");
    }

    @Override
    public jakarta.persistence.criteria.Predicate like(jakarta.persistence.criteria.Expression<java.lang.String> a0, jakarta.persistence.criteria.Expression<java.lang.String> a1, char a2) {
        throw unsupported("like");
    }

    @Override
    public jakarta.persistence.criteria.Predicate like(jakarta.persistence.criteria.Expression<java.lang.String> a0, java.lang.String a1, jakarta.persistence.criteria.Expression<java.lang.Character> a2) {
        throw unsupported("like");
    }

    @Override
    public jakarta.persistence.criteria.Predicate like(jakarta.persistence.criteria.Expression<java.lang.String> a0, java.lang.String a1, char a2) {
        throw unsupported("like");
    }

    @Override
    public jakarta.persistence.criteria.Predicate notLike(jakarta.persistence.criteria.Expression<java.lang.String> a0, jakarta.persistence.criteria.Expression<java.lang.String> a1) {
        throw unsupported("notLike");
    }

    @Override
    public jakarta.persistence.criteria.Predicate notLike(jakarta.persistence.criteria.Expression<java.lang.String> a0, java.lang.String a1) {
        throw unsupported("notLike");
    }

    @Override
    public jakarta.persistence.criteria.Predicate notLike(jakarta.persistence.criteria.Expression<java.lang.String> a0, jakarta.persistence.criteria.Expression<java.lang.String> a1, jakarta.persistence.criteria.Expression<java.lang.Character> a2) {
        throw unsupported("notLike");
    }

    @Override
    public jakarta.persistence.criteria.Predicate notLike(jakarta.persistence.criteria.Expression<java.lang.String> a0, jakarta.persistence.criteria.Expression<java.lang.String> a1, char a2) {
        throw unsupported("notLike");
    }

    @Override
    public jakarta.persistence.criteria.Predicate notLike(jakarta.persistence.criteria.Expression<java.lang.String> a0, java.lang.String a1, jakarta.persistence.criteria.Expression<java.lang.Character> a2) {
        throw unsupported("notLike");
    }

    @Override
    public jakarta.persistence.criteria.Predicate notLike(jakarta.persistence.criteria.Expression<java.lang.String> a0, java.lang.String a1, char a2) {
        throw unsupported("notLike");
    }

    @Override
    public jakarta.persistence.criteria.Expression<java.lang.String> concat(java.util.List<jakarta.persistence.criteria.Expression<java.lang.String>> a0) {
        throw unsupported("concat");
    }

    @Override
    public jakarta.persistence.criteria.Expression<java.lang.String> concat(jakarta.persistence.criteria.Expression<java.lang.String> a0, jakarta.persistence.criteria.Expression<java.lang.String> a1) {
        throw unsupported("concat");
    }

    @Override
    public jakarta.persistence.criteria.Expression<java.lang.String> concat(jakarta.persistence.criteria.Expression<java.lang.String> a0, java.lang.String a1) {
        throw unsupported("concat");
    }

    @Override
    public jakarta.persistence.criteria.Expression<java.lang.String> concat(java.lang.String a0, jakarta.persistence.criteria.Expression<java.lang.String> a1) {
        throw unsupported("concat");
    }

    @Override
    public jakarta.persistence.criteria.Expression<java.lang.String> substring(jakarta.persistence.criteria.Expression<java.lang.String> a0, jakarta.persistence.criteria.Expression<java.lang.Integer> a1) {
        throw unsupported("substring");
    }

    @Override
    public jakarta.persistence.criteria.Expression<java.lang.String> substring(jakarta.persistence.criteria.Expression<java.lang.String> a0, int a1) {
        throw unsupported("substring");
    }

    @Override
    public jakarta.persistence.criteria.Expression<java.lang.String> substring(jakarta.persistence.criteria.Expression<java.lang.String> a0, jakarta.persistence.criteria.Expression<java.lang.Integer> a1, jakarta.persistence.criteria.Expression<java.lang.Integer> a2) {
        throw unsupported("substring");
    }

    @Override
    public jakarta.persistence.criteria.Expression<java.lang.String> substring(jakarta.persistence.criteria.Expression<java.lang.String> a0, int a1, int a2) {
        throw unsupported("substring");
    }

    @Override
    public jakarta.persistence.criteria.Expression<java.lang.String> trim(jakarta.persistence.criteria.Expression<java.lang.String> a0) {
        throw unsupported("trim");
    }

    @Override
    public jakarta.persistence.criteria.Expression<java.lang.String> trim(jakarta.persistence.criteria.CriteriaBuilder.Trimspec a0, jakarta.persistence.criteria.Expression<java.lang.String> a1) {
        throw unsupported("trim");
    }

    @Override
    public jakarta.persistence.criteria.Expression<java.lang.String> trim(jakarta.persistence.criteria.Expression<java.lang.Character> a0, jakarta.persistence.criteria.Expression<java.lang.String> a1) {
        throw unsupported("trim");
    }

    @Override
    public jakarta.persistence.criteria.Expression<java.lang.String> trim(jakarta.persistence.criteria.CriteriaBuilder.Trimspec a0, jakarta.persistence.criteria.Expression<java.lang.Character> a1, jakarta.persistence.criteria.Expression<java.lang.String> a2) {
        throw unsupported("trim");
    }

    @Override
    public jakarta.persistence.criteria.Expression<java.lang.String> trim(char a0, jakarta.persistence.criteria.Expression<java.lang.String> a1) {
        throw unsupported("trim");
    }

    @Override
    public jakarta.persistence.criteria.Expression<java.lang.String> trim(jakarta.persistence.criteria.CriteriaBuilder.Trimspec a0, char a1, jakarta.persistence.criteria.Expression<java.lang.String> a2) {
        throw unsupported("trim");
    }

    @Override
    public jakarta.persistence.criteria.Expression<java.lang.String> lower(jakarta.persistence.criteria.Expression<java.lang.String> a0) {
        throw unsupported("lower");
    }

    @Override
    public jakarta.persistence.criteria.Expression<java.lang.String> upper(jakarta.persistence.criteria.Expression<java.lang.String> a0) {
        throw unsupported("upper");
    }

    @Override
    public jakarta.persistence.criteria.Expression<java.lang.Integer> length(jakarta.persistence.criteria.Expression<java.lang.String> a0) {
        throw unsupported("length");
    }

    @Override
    public jakarta.persistence.criteria.Expression<java.lang.String> left(jakarta.persistence.criteria.Expression<java.lang.String> a0, int a1) {
        throw unsupported("left");
    }

    @Override
    public jakarta.persistence.criteria.Expression<java.lang.String> right(jakarta.persistence.criteria.Expression<java.lang.String> a0, int a1) {
        throw unsupported("right");
    }

    @Override
    public jakarta.persistence.criteria.Expression<java.lang.String> left(jakarta.persistence.criteria.Expression<java.lang.String> a0, jakarta.persistence.criteria.Expression<java.lang.Integer> a1) {
        throw unsupported("left");
    }

    @Override
    public jakarta.persistence.criteria.Expression<java.lang.String> right(jakarta.persistence.criteria.Expression<java.lang.String> a0, jakarta.persistence.criteria.Expression<java.lang.Integer> a1) {
        throw unsupported("right");
    }

    @Override
    public jakarta.persistence.criteria.Expression<java.lang.String> replace(jakarta.persistence.criteria.Expression<java.lang.String> a0, jakarta.persistence.criteria.Expression<java.lang.String> a1, jakarta.persistence.criteria.Expression<java.lang.String> a2) {
        throw unsupported("replace");
    }

    @Override
    public jakarta.persistence.criteria.Expression<java.lang.String> replace(jakarta.persistence.criteria.Expression<java.lang.String> a0, java.lang.String a1, jakarta.persistence.criteria.Expression<java.lang.String> a2) {
        throw unsupported("replace");
    }

    @Override
    public jakarta.persistence.criteria.Expression<java.lang.String> replace(jakarta.persistence.criteria.Expression<java.lang.String> a0, jakarta.persistence.criteria.Expression<java.lang.String> a1, java.lang.String a2) {
        throw unsupported("replace");
    }

    @Override
    public jakarta.persistence.criteria.Expression<java.lang.String> replace(jakarta.persistence.criteria.Expression<java.lang.String> a0, java.lang.String a1, java.lang.String a2) {
        throw unsupported("replace");
    }

    @Override
    public jakarta.persistence.criteria.Expression<java.lang.Integer> locate(jakarta.persistence.criteria.Expression<java.lang.String> a0, jakarta.persistence.criteria.Expression<java.lang.String> a1) {
        throw unsupported("locate");
    }

    @Override
    public jakarta.persistence.criteria.Expression<java.lang.Integer> locate(jakarta.persistence.criteria.Expression<java.lang.String> a0, java.lang.String a1) {
        throw unsupported("locate");
    }

    @Override
    public jakarta.persistence.criteria.Expression<java.lang.Integer> locate(jakarta.persistence.criteria.Expression<java.lang.String> a0, jakarta.persistence.criteria.Expression<java.lang.String> a1, jakarta.persistence.criteria.Expression<java.lang.Integer> a2) {
        throw unsupported("locate");
    }

    @Override
    public jakarta.persistence.criteria.Expression<java.lang.Integer> locate(jakarta.persistence.criteria.Expression<java.lang.String> a0, java.lang.String a1, int a2) {
        throw unsupported("locate");
    }

    @Override
    public jakarta.persistence.criteria.Expression<java.sql.Date> currentDate() {
        throw unsupported("currentDate");
    }

    @Override
    public jakarta.persistence.criteria.Expression<java.sql.Timestamp> currentTimestamp() {
        throw unsupported("currentTimestamp");
    }

    @Override
    public jakarta.persistence.criteria.Expression<java.sql.Time> currentTime() {
        throw unsupported("currentTime");
    }

    @Override
    public jakarta.persistence.criteria.Expression<java.time.LocalDate> localDate() {
        throw unsupported("localDate");
    }

    @Override
    public jakarta.persistence.criteria.Expression<java.time.LocalDateTime> localDateTime() {
        throw unsupported("localDateTime");
    }

    @Override
    public jakarta.persistence.criteria.Expression<java.time.LocalTime> localTime() {
        throw unsupported("localTime");
    }

    @Override
    public <N, T extends java.time.temporal.Temporal> jakarta.persistence.criteria.Expression<N> extract(jakarta.persistence.criteria.TemporalField<N, T> a0, jakarta.persistence.criteria.Expression<T> a1) {
        throw unsupported("extract");
    }

    @Override
    public <T> jakarta.persistence.criteria.CriteriaBuilder.In<T> in(jakarta.persistence.criteria.Expression<? extends T> a0) {
        throw unsupported("in");
    }

    @Override
    public <Y> jakarta.persistence.criteria.Expression<Y> coalesce(jakarta.persistence.criteria.Expression<? extends Y> a0, jakarta.persistence.criteria.Expression<? extends Y> a1) {
        throw unsupported("coalesce");
    }

    @Override
    public <Y> jakarta.persistence.criteria.Expression<Y> coalesce(jakarta.persistence.criteria.Expression<? extends Y> a0, Y a1) {
        throw unsupported("coalesce");
    }

    @Override
    public <Y> jakarta.persistence.criteria.Expression<Y> nullif(jakarta.persistence.criteria.Expression<Y> a0, jakarta.persistence.criteria.Expression<?> a1) {
        throw unsupported("nullif");
    }

    @Override
    public <Y> jakarta.persistence.criteria.Expression<Y> nullif(jakarta.persistence.criteria.Expression<Y> a0, Y a1) {
        throw unsupported("nullif");
    }

    @Override
    public <T> jakarta.persistence.criteria.CriteriaBuilder.Coalesce<T> coalesce() {
        throw unsupported("coalesce");
    }

    @Override
    public <C, R> jakarta.persistence.criteria.CriteriaBuilder.SimpleCase<C, R> selectCase(jakarta.persistence.criteria.Expression<? extends C> a0) {
        throw unsupported("selectCase");
    }

    @Override
    public <R> jakarta.persistence.criteria.CriteriaBuilder.Case<R> selectCase() {
        throw unsupported("selectCase");
    }

    @Override
    public <T> jakarta.persistence.criteria.Expression<T> function(java.lang.String a0, java.lang.Class<T> a1, jakarta.persistence.criteria.Expression<?>... a2) {
        throw unsupported("function");
    }

    @Override
    public <X, T, V extends T> jakarta.persistence.criteria.Join<X, V> treat(jakarta.persistence.criteria.Join<X, T> a0, java.lang.Class<V> a1) {
        throw unsupported("treat");
    }

    @Override
    public <X, T, E extends T> jakarta.persistence.criteria.CollectionJoin<X, E> treat(jakarta.persistence.criteria.CollectionJoin<X, T> a0, java.lang.Class<E> a1) {
        throw unsupported("treat");
    }

    @Override
    public <X, T, E extends T> jakarta.persistence.criteria.SetJoin<X, E> treat(jakarta.persistence.criteria.SetJoin<X, T> a0, java.lang.Class<E> a1) {
        throw unsupported("treat");
    }

    @Override
    public <X, T, E extends T> jakarta.persistence.criteria.ListJoin<X, E> treat(jakarta.persistence.criteria.ListJoin<X, T> a0, java.lang.Class<E> a1) {
        throw unsupported("treat");
    }

    @Override
    public <X, K, T, V extends T> jakarta.persistence.criteria.MapJoin<X, K, V> treat(jakarta.persistence.criteria.MapJoin<X, K, T> a0, java.lang.Class<V> a1) {
        throw unsupported("treat");
    }

    @Override
    public <X, T extends X> jakarta.persistence.criteria.Path<T> treat(jakarta.persistence.criteria.Path<X> a0, java.lang.Class<T> a1) {
        throw unsupported("treat");
    }

    @Override
    public <X, T extends X> jakarta.persistence.criteria.Root<T> treat(jakarta.persistence.criteria.Root<X> a0, java.lang.Class<T> a1) {
        throw unsupported("treat");
    }

    @Override
    public <T> jakarta.persistence.criteria.CriteriaSelect<T> union(jakarta.persistence.criteria.CriteriaSelect<? extends T> a0, jakarta.persistence.criteria.CriteriaSelect<? extends T> a1) {
        throw unsupported("union");
    }

    @Override
    public <T> jakarta.persistence.criteria.CriteriaSelect<T> unionAll(jakarta.persistence.criteria.CriteriaSelect<? extends T> a0, jakarta.persistence.criteria.CriteriaSelect<? extends T> a1) {
        throw unsupported("unionAll");
    }

    @Override
    public <T> jakarta.persistence.criteria.CriteriaSelect<T> intersect(jakarta.persistence.criteria.CriteriaSelect<? super T> a0, jakarta.persistence.criteria.CriteriaSelect<? super T> a1) {
        throw unsupported("intersect");
    }

    @Override
    public <T> jakarta.persistence.criteria.CriteriaSelect<T> intersectAll(jakarta.persistence.criteria.CriteriaSelect<? super T> a0, jakarta.persistence.criteria.CriteriaSelect<? super T> a1) {
        throw unsupported("intersectAll");
    }

    @Override
    public <T> jakarta.persistence.criteria.CriteriaSelect<T> except(jakarta.persistence.criteria.CriteriaSelect<T> a0, jakarta.persistence.criteria.CriteriaSelect<?> a1) {
        throw unsupported("except");
    }

    @Override
    public <T> jakarta.persistence.criteria.CriteriaSelect<T> exceptAll(jakarta.persistence.criteria.CriteriaSelect<T> a0, jakarta.persistence.criteria.CriteriaSelect<?> a1) {
        throw unsupported("exceptAll");
    }
}
