package io.nova.query;

import java.util.List;

public final class Criteria {
    private Criteria() {
    }

    public static Condition eq(String property, Object value) {
        return new Condition(property, ComparisonOperator.EQ, value);
    }

    public static Condition ne(String property, Object value) {
        return new Condition(property, ComparisonOperator.NE, value);
    }

    public static Condition gt(String property, Object value) {
        return new Condition(property, ComparisonOperator.GT, value);
    }

    public static Condition gte(String property, Object value) {
        return new Condition(property, ComparisonOperator.GTE, value);
    }

    public static Condition lt(String property, Object value) {
        return new Condition(property, ComparisonOperator.LT, value);
    }

    public static Condition lte(String property, Object value) {
        return new Condition(property, ComparisonOperator.LTE, value);
    }

    public static Condition like(String property, Object value) {
        return new Condition(property, ComparisonOperator.LIKE, value);
    }

    public static Condition isNull(String property) {
        return new Condition(property, ComparisonOperator.IS_NULL, null);
    }

    public static Condition isNotNull(String property) {
        return new Condition(property, ComparisonOperator.IS_NOT_NULL, null);
    }

    public static CompoundPredicate and(Predicate... predicates) {
        return new CompoundPredicate(LogicalOperator.AND, List.of(predicates));
    }

    public static CompoundPredicate or(Predicate... predicates) {
        return new CompoundPredicate(LogicalOperator.OR, List.of(predicates));
    }
}
