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
     * Verifies that every given entity's primary and secondary tables and mapped
     * columns exist. Completes empty when all are present, or errors with the
     * collected missing-table/column problems. Table and column names are compared
     * case-insensitively so dialect identifier case-folding does not cause false
     * negatives. Column types and constraints are not compared.
     */
    Mono<Void> validate(Iterable<Class<?>> entityTypes);
}
