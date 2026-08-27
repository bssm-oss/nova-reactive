package io.nova.metadata;

import java.util.Objects;

/**
 * Immutable physical DDL metadata for a JPA {@code CheckConstraint}. Constraint and options are
 * trusted annotation fragments, deliberately kept verbatim (apart from the factory's trimming).
 */
public record CheckConstraintDefinition(String name, String constraint, String options) {
    public CheckConstraintDefinition {
        name = name == null ? "" : name;
        constraint = Objects.requireNonNull(constraint, "constraint must not be null");
        options = options == null ? "" : options;
    }
}
