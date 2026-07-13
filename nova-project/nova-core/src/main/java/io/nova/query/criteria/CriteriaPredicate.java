package io.nova.query.criteria;

import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code jakarta.persistence.criteria.Predicate} 구현. 논리 결합(AND/OR/NOT)과 leaf 술어(비교/LIKE/
 * BETWEEN/IN/NULL)를 하나의 구조 노드로 표현하며, 실행 시 {@link CriteriaEntityTranslator}(엔티티 경로)
 * 또는 {@link CriteriaSqlBuilder}(스칼라 경로)가 이 구조를 각각 Nova {@code Criteria} 또는 SQL로 변환한다.
 * predicate 조립은 {@link SimpleCriteriaBuilder}의 정적 팩토리를 통해서만 이뤄진다.
 */
class CriteriaPredicate extends AbstractCriteriaExpression<Boolean> implements Predicate {

    enum Kind { AND, OR, NOT, COMPARISON, LIKE, BETWEEN, IN, NULL }

    private final Kind kind;
    private final CriteriaColumnPath path;
    private final CompareOp op;
    private final Object value;
    private final Object low;
    private final Object high;
    private final List<Object> inValues;
    private final List<CriteriaPredicate> children;
    private final CriteriaPredicate inner;
    private final boolean negated;

    CriteriaPredicate(
            Kind kind,
            CriteriaColumnPath path,
            CompareOp op,
            Object value,
            Object low,
            Object high,
            List<Object> inValues,
            List<CriteriaPredicate> children,
            CriteriaPredicate inner,
            boolean negated) {
        super(Boolean.class);
        this.kind = kind;
        this.path = path;
        this.op = op;
        this.value = value;
        this.low = low;
        this.high = high;
        this.inValues = inValues;
        this.children = children;
        this.inner = inner;
        this.negated = negated;
    }

    // --- factories ----------------------------------------------------------------------------

    static CriteriaPredicate comparison(CriteriaColumnPath path, CompareOp op, Object value) {
        return new CriteriaPredicate(Kind.COMPARISON, path, op, value, null, null, null, null, null, false);
    }

    static CriteriaPredicate like(CriteriaColumnPath path, Object pattern, boolean negated) {
        return new CriteriaPredicate(Kind.LIKE, path, null, pattern, null, null, null, null, null, negated);
    }

    static CriteriaPredicate between(CriteriaColumnPath path, Object low, Object high) {
        return new CriteriaPredicate(Kind.BETWEEN, path, null, null, low, high, null, null, null, false);
    }

    static CriteriaPredicate in(CriteriaColumnPath path, List<Object> values, boolean negated) {
        return new CriteriaPredicate(Kind.IN, path, null, null, null, null, new ArrayList<>(values), null, null, negated);
    }

    static CriteriaPredicate isNull(CriteriaColumnPath path) {
        return new CriteriaPredicate(Kind.NULL, path, null, null, null, null, null, null, null, false);
    }

    static CriteriaPredicate isNotNull(CriteriaColumnPath path) {
        return new CriteriaPredicate(Kind.NULL, path, null, null, null, null, null, null, null, true);
    }

    static CriteriaPredicate junction(Kind kind, List<CriteriaPredicate> children) {
        return new CriteriaPredicate(kind, null, null, null, null, null, null, new ArrayList<>(children), null, false);
    }

    static CriteriaPredicate negate(CriteriaPredicate inner) {
        return new CriteriaPredicate(Kind.NOT, null, null, null, null, null, null, null, inner, true);
    }

    // --- structural accessors -----------------------------------------------------------------

    Kind kind() {
        return kind;
    }

    CriteriaColumnPath path() {
        return path;
    }

    CompareOp op() {
        return op;
    }

    Object value() {
        return value;
    }

    Object low() {
        return low;
    }

    Object high() {
        return high;
    }

    List<Object> inValues() {
        return inValues;
    }

    List<CriteriaPredicate> children() {
        return children;
    }

    CriteriaPredicate inner() {
        return inner;
    }

    boolean negated() {
        return negated;
    }

    // --- jakarta Predicate --------------------------------------------------------------------

    @Override
    public BooleanOperator getOperator() {
        return kind == Kind.OR ? BooleanOperator.OR : BooleanOperator.AND;
    }

    @Override
    public boolean isNegated() {
        return negated;
    }

    @Override
    public List<Expression<Boolean>> getExpressions() {
        if (children == null) {
            return List.of();
        }
        return List.copyOf(children);
    }

    @Override
    public Predicate not() {
        return negate(this);
    }
}
