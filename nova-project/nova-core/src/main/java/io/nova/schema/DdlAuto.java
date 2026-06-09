package io.nova.schema;

/**
 * Schema lifecycle policy mirrored from JPA's {@code hibernate.hbm2ddl.auto}
 * but constrained to what {@link SchemaInitializer} can do today.
 *
 * <p>Phase 1 supports the three modes that do not require schema introspection
 * or diffing. {@code VALIDATE} and {@code UPDATE} are deliberately omitted
 * because they require querying the live database catalog, which is dialect
 * work not yet in Nova.
 */
public enum DdlAuto {
    /** Do nothing on startup. */
    NONE,

    /**
     * Drop the configured tables (if any) and recreate them on startup. Useful
     * for integration tests and demo seeding. Production should use a real
     * migration tool such as Flyway or Liquibase instead.
     */
    CREATE,

    /**
     * Same as {@link #CREATE} but also drops the tables on context shutdown,
     * matching the JPA {@code create-drop} mode. The drop runs during
     * {@code DisposableBean#destroy()}.
     */
    CREATE_DROP
}
