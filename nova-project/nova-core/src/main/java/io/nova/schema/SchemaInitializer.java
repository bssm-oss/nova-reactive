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
 * For the batch variants, statements are emitted sequentially in the order the
 * classes are given. Callers must order parent entities before children when
 * foreign keys are involved.
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
}
