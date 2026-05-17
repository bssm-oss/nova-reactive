package io.nova.query;

import java.util.List;

public record CompoundPredicate(LogicalOperator operator, List<Predicate> predicates) implements Predicate {
    public CompoundPredicate {
        predicates = List.copyOf(predicates);
    }
}
