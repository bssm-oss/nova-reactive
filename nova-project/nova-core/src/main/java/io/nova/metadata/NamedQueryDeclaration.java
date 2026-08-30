package io.nova.metadata;

import java.util.Objects;

/**
 * An immutable named-query definition together with the physical class that declares its
 * annotation. A mapped-superclass declaration can be discovered through multiple concrete
 * descendants; its declaring type distinguishes that single declaration from a conflicting
 * declaration with the same query name.
 */
public record NamedQueryDeclaration(
        NamedQueryDefinition definition, Class<?> declaringType, int declarationIndex) {

    public NamedQueryDeclaration {
        Objects.requireNonNull(definition, "definition must not be null");
        Objects.requireNonNull(declaringType, "declaringType must not be null");
        if (declarationIndex < 0) {
            throw new IllegalArgumentException("declarationIndex must not be negative");
        }
    }
}
