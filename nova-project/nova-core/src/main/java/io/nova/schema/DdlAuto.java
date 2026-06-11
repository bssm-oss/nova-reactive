package io.nova.schema;

/**
 * Schema lifecycle policy mirrored from JPA's {@code spring.jpa.hibernate.ddl-auto} /
 * {@code hibernate.hbm2ddl.auto}. All five JPA values bind, so a JPA config can be
 * copied as-is; what Nova can actually execute differs where live-catalog
 * introspection would be required.
 */
public enum DdlAuto {
    /** Do nothing on startup. */
    NONE,

    /**
     * Validate that the schema matches the entities. Not yet supported — it requires
     * querying the live database catalog, which Nova does not do today. Selecting it
     * fails fast at startup with a clear message rather than silently doing nothing;
     * use a migration tool (Flyway, Liquibase) for validation.
     */
    VALIDATE,

    /**
     * Create any missing tables without dropping existing ones
     * ({@code CREATE TABLE IF NOT EXISTS}). Non-destructive. Unlike Hibernate's
     * {@code update}, Nova does not diff and {@code ALTER} existing tables to add
     * missing columns — only whole missing tables are created.
     */
    UPDATE,

    /**
     * Drop the configured tables (if any) and recreate them on startup, matching
     * Hibernate's destructive {@code create}. Useful for integration tests and demo
     * seeding. Production should use a real migration tool instead.
     */
    CREATE,

    /**
     * Same as {@link #CREATE} but also drops the tables on context shutdown,
     * matching the JPA {@code create-drop} mode. The drop runs during
     * {@code DisposableBean#destroy()}.
     */
    CREATE_DROP
}
