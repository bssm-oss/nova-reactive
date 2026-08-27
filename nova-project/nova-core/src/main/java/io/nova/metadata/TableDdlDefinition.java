package io.nova.metadata;

import java.util.List;

/** DDL-only attributes contributed by JPA {@code Table}. */
public record TableDdlDefinition(List<CheckConstraintDefinition> checks, String comment, String options) {
    public static final TableDdlDefinition EMPTY = new TableDdlDefinition(List.of(), "", "");

    public TableDdlDefinition {
        checks = checks == null ? List.of() : List.copyOf(checks);
        comment = comment == null ? "" : comment;
        options = options == null ? "" : options;
    }
}
