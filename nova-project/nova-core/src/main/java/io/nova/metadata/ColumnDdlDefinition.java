package io.nova.metadata;

/** DDL-only attributes contributed by the effective JPA {@code Column}. */
public record ColumnDdlDefinition(
        java.util.List<CheckConstraintDefinition> checks,
        String comment,
        String options,
        int secondPrecision
) {
    public static final ColumnDdlDefinition EMPTY = new ColumnDdlDefinition(java.util.List.of(), "", "", -1);

    public ColumnDdlDefinition {
        checks = checks == null ? java.util.List.of() : java.util.List.copyOf(checks);
        comment = comment == null ? "" : comment;
        options = options == null ? "" : options;
    }
}
