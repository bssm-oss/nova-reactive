package io.nova.query;

import java.util.Objects;

/**
 * 다른 predicate를 부정한다. SQL상 {@code not (...)}으로 렌더된다.
 */
public record NegationPredicate(Predicate inner) implements Predicate {
    public NegationPredicate {
        Objects.requireNonNull(inner, "inner predicate must not be null");
    }
}
