package io.nova.schema;

/**
 * Immutable knobs accepted by every {@link SchemaInitializer} method.
 *
 * <ul>
 *   <li>{@link #ifNotExists()} — when {@code true} (the default), {@code CREATE}
 *       calls use {@code CREATE TABLE IF NOT EXISTS} so re-runs are idempotent.
 *       When {@code false}, the raw {@code CREATE TABLE} is emitted and a
 *       second run on the same table will fail with a dialect-specific error.
 *       Affects {@code drop} the same way via {@code DROP TABLE IF EXISTS}.
 *   </li>
 *   <li>{@link #includeIndexes()} — when {@code true} (the default), every
 *       {@code @Index} and {@code @UniqueConstraint} declared on the entity is
 *       emitted after the {@code CREATE TABLE}. Set {@code false} when index
 *       creation is owned by a separate migration step.
 *   </li>
 * </ul>
 */
public final class SchemaOptions {

    private static final SchemaOptions DEFAULTS = new SchemaOptions(true, true);

    private final boolean ifNotExists;
    private final boolean includeIndexes;

    private SchemaOptions(boolean ifNotExists, boolean includeIndexes) {
        this.ifNotExists = ifNotExists;
        this.includeIndexes = includeIndexes;
    }

    /** Returns the default options: idempotent DDL + indexes included. */
    public static SchemaOptions defaults() {
        return DEFAULTS;
    }

    public boolean ifNotExists() {
        return ifNotExists;
    }

    public boolean includeIndexes() {
        return includeIndexes;
    }

    public SchemaOptions withIfNotExists(boolean value) {
        return value == ifNotExists ? this : new SchemaOptions(value, includeIndexes);
    }

    public SchemaOptions withIncludeIndexes(boolean value) {
        return value == includeIndexes ? this : new SchemaOptions(ifNotExists, value);
    }
}
