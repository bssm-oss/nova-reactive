package io.nova.query;

public sealed interface Predicate permits Condition, CompoundPredicate {
}
