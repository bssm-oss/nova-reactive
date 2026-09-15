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
     * Validate catalog-visible tables and columns represented by each ordinary entity
     * or collapsed inheritance root, failing startup with the collected problems.
     * Ordinary entity primary columns, secondary tables, and their mapped columns
     * are checked. For every inheritance strategy, the collapsed root's primary
     * mapped columns and configured discriminator are checked, along with secondary
     * tables represented on that root metadata.
     * {@code JOINED}/{@code TABLE_PER_CLASS} subtype tables, subtype-only secondary
     * tables, generator/join/collection tables, order columns, indexes, constraints,
     * and column types are not checked. {@code TABLE_PER_CLASS} validation still
     * targets the root table/discriminator even though creation emits subtype tables,
     * so it is not a complete or appropriate physical-schema check for that strategy.
     * Names are compared case-insensitively. Use a migration tool such as Flyway or
     * Liquibase for complete validation.
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
