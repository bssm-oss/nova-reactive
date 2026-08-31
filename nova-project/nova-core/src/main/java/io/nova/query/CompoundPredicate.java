package io.nova.query;

import java.util.List;
import java.util.Objects;

public record CompoundPredicate(LogicalOperator operator, List<Predicate> predicates) implements Predicate {
    public CompoundPredicate {
        Objects.requireNonNull(operator, "operator must not be null");
        predicates = List.copyOf(predicates);
    }
}
