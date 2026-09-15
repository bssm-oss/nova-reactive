package io.nova.schema;

import reactor.core.publisher.Mono;

/**
 * Reactive helper that issues {@code CREATE TABLE} / {@code DROP TABLE} / etc.
 * directly from entity classes. It is intended for development, integration
 * tests, and demo seeding — production deployments should manage schema with
 * a dedicated migration tool (Flyway, Liquibase, etc.).
 *
 * <p>Methods exist in three shapes for convenience:
 * <ul>
 *   <li>{@code create(Class)} — single entity</li>
 *   <li>{@code create(Class...)} — varargs batch</li>
 *   <li>{@code create(Iterable)} — programmatic batch</li>
 * </ul>
 * For the batch variants, work is emitted sequentially within each schema phase.
 * Creation provisions table generators, entity/secondary tables, order columns,
 * join and collection tables, then foreign keys. Deferring foreign keys until all
 * tables exist means callers do not need to place parent entities before children.
 * Drop removes collection and join tables first, then entity tables in the supplied
 * order, and finally table generators; callers must still place child entities before
 * parents when entity-to-entity foreign keys are involved. Recreate reverses the
 * supplied entity order for its drop phase and uses the supplied order for creation.
 *
 * <p>Every {@link Mono} returned is cold: nothing runs until subscribed. The
 * Mono completes with no value (use {@link Mono#then()} to chain follow-up
 * work) and propagates the underlying R2DBC error on failure.
 */
public interface SchemaInitializer {

    Mono<Void> create(Class<?> entityType);

    Mono<Void> create(Class<?> entityType, SchemaOptions options);

    Mono<Void> create(Class<?>... entityTypes);

    Mono<Void> create(Iterable<Class<?>> entityTypes);

    Mono<Void> create(Iterable<Class<?>> entityTypes, SchemaOptions options);

    Mono<Void> drop(Class<?> entityType);

    Mono<Void> drop(Class<?> entityType, SchemaOptions options);

    Mono<Void> drop(Class<?>... entityTypes);

    Mono<Void> drop(Iterable<Class<?>> entityTypes);

    Mono<Void> drop(Iterable<Class<?>> entityTypes, SchemaOptions options);

    /**
     * Drops the table (if it exists) and recreates it. Equivalent to
     * {@code drop(...).then(create(...))} with {@code ifNotExists=false} on the
     * create step so a stale leftover table surfaces as a clear error rather
     * than being silently reused.
     */
    Mono<Void> recreate(Class<?> entityType);

    Mono<Void> recreate(Class<?>... entityTypes);

    Mono<Void> recreate(Iterable<Class<?>> entityTypes);

    /**
     * Verifies catalog-visible tables and columns represented by each ordinary entity
     * or collapsed inheritance root. Ordinary entity primary columns, secondary
     * tables, and their mapped columns are checked. For every inheritance strategy,
     * the collapsed root's primary mapped columns and configured discriminator are
     * checked, along with secondary tables represented on that root metadata.
     * Completes empty when that scope is present, or errors with the collected
     * missing-table/column problems. Table and column names are compared
     * case-insensitively.
     * {@code JOINED}/{@code TABLE_PER_CLASS} subtype tables,
     * subtype-only secondary tables, generator/join/collection tables, order columns,
     * indexes, constraints, and column types are not checked. For
     * {@code TABLE_PER_CLASS}, validation still targets the root table/discriminator
     * even though creation emits subtype tables, so it is not a complete or
     * appropriate physical-schema check for that strategy.
     */
    Mono<Void> validate(Iterable<Class<?>> entityTypes);
}
