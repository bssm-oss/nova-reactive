package io.nova.query;

public record Condition(String property, ComparisonOperator operator, Object value) implements Predicate {
}
