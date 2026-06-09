package io.nova.schema;

import io.nova.core.ReactiveEntityOperations;
import io.nova.metadata.EntityMetadata;
import io.nova.metadata.EntityMetadataFactory;
import io.nova.query.NativeQuery;
import io.nova.sql.Dialect;
import io.nova.sql.SchemaGenerator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Default {@link SchemaInitializer} that delegates DDL execution to the host
 * {@link ReactiveEntityOperations#executeNative(NativeQuery)} path. The
 * implementation deliberately holds no schema-cache or runtime state — every
 * call re-resolves the {@link EntityMetadata} through the injected
 * {@link EntityMetadataFactory} (which is itself cache-backed) and issues a
 * fresh DDL string.
 *
 * <p>Batch ordering: when multiple entity types are passed, statements run
 * sequentially in the iteration order. For {@code drop(...)}, this means
 * children must be listed before parents when FKs are in play; for
 * {@code create(...)}, parents before children.
 */
public final class SimpleSchemaInitializer implements SchemaInitializer {

    private final ReactiveEntityOperations operations;
    private final EntityMetadataFactory metadataFactory;
    private final Dialect dialect;

    public SimpleSchemaInitializer(
            ReactiveEntityOperations operations,
            EntityMetadataFactory metadataFactory,
            Dialect dialect) {
        this.operations = Objects.requireNonNull(operations, "operations must not be null");
        this.metadataFactory = Objects.requireNonNull(metadataFactory, "metadataFactory must not be null");
        this.dialect = Objects.requireNonNull(dialect, "dialect must not be null");
    }

    @Override
    public Mono<Void> create(Class<?> entityType) {
        return create(entityType, SchemaOptions.defaults());
    }

    @Override
    public Mono<Void> create(Class<?> entityType, SchemaOptions options) {
        Objects.requireNonNull(entityType, "entityType must not be null");
        Objects.requireNonNull(options, "options must not be null");
        return Mono.defer(() -> createOne(entityType, options));
    }

    @Override
    public Mono<Void> create(Class<?>... entityTypes) {
        Objects.requireNonNull(entityTypes, "entityTypes must not be null");
        return create(List.of(entityTypes));
    }

    @Override
    public Mono<Void> create(Iterable<Class<?>> entityTypes) {
        return create(entityTypes, SchemaOptions.defaults());
    }

    @Override
    public Mono<Void> create(Iterable<Class<?>> entityTypes, SchemaOptions options) {
        Objects.requireNonNull(entityTypes, "entityTypes must not be null");
        Objects.requireNonNull(options, "options must not be null");
        return Flux.fromIterable(copyOf(entityTypes))
                .concatMap(type -> createOne(type, options))
                .then();
    }

    @Override
    public Mono<Void> drop(Class<?> entityType) {
        return drop(entityType, SchemaOptions.defaults());
    }

    @Override
    public Mono<Void> drop(Class<?> entityType, SchemaOptions options) {
        Objects.requireNonNull(entityType, "entityType must not be null");
        Objects.requireNonNull(options, "options must not be null");
        return Mono.defer(() -> dropOne(entityType, options));
    }

    @Override
    public Mono<Void> drop(Class<?>... entityTypes) {
        Objects.requireNonNull(entityTypes, "entityTypes must not be null");
        return drop(List.of(entityTypes));
    }

    @Override
    public Mono<Void> drop(Iterable<Class<?>> entityTypes) {
        return drop(entityTypes, SchemaOptions.defaults());
    }

    @Override
    public Mono<Void> drop(Iterable<Class<?>> entityTypes, SchemaOptions options) {
        Objects.requireNonNull(entityTypes, "entityTypes must not be null");
        Objects.requireNonNull(options, "options must not be null");
        return Flux.fromIterable(copyOf(entityTypes))
                .concatMap(type -> dropOne(type, options))
                .then();
    }

    @Override
    public Mono<Void> recreate(Class<?> entityType) {
        Objects.requireNonNull(entityType, "entityType must not be null");
        // drop uses IF EXISTS (idempotent), create uses raw CREATE TABLE so a stale
        // leftover surfaces as a clear error rather than being silently reused.
        SchemaOptions dropOptions = SchemaOptions.defaults().withIfNotExists(true);
        SchemaOptions createOptions = SchemaOptions.defaults().withIfNotExists(false);
        return dropOne(entityType, dropOptions).then(Mono.defer(() -> createOne(entityType, createOptions)));
    }

    @Override
    public Mono<Void> recreate(Class<?>... entityTypes) {
        Objects.requireNonNull(entityTypes, "entityTypes must not be null");
        return recreate(List.of(entityTypes));
    }

    @Override
    public Mono<Void> recreate(Iterable<Class<?>> entityTypes) {
        Objects.requireNonNull(entityTypes, "entityTypes must not be null");
        // Reverse drop order vs create so child tables are dropped before their parents
        // (FK constraint friendly) and parents are created before children.
        List<Class<?>> ordered = copyOf(entityTypes);
        List<Class<?>> reversed = new ArrayList<>(ordered);
        java.util.Collections.reverse(reversed);
        SchemaOptions dropOptions = SchemaOptions.defaults().withIfNotExists(true);
        SchemaOptions createOptions = SchemaOptions.defaults().withIfNotExists(false);
        return Flux.fromIterable(reversed)
                .concatMap(type -> dropOne(type, dropOptions))
                .then(Flux.fromIterable(ordered)
                        .concatMap(type -> createOne(type, createOptions))
                        .then());
    }

    private Mono<Void> createOne(Class<?> entityType, SchemaOptions options) {
        EntityMetadata<?> metadata = metadataFactory.getEntityMetadata(entityType);
        SchemaGenerator generator = dialect.schemaGenerator();
        String createDdl = options.ifNotExists()
                ? generator.createTableIfNotExists(metadata)
                : generator.createTable(metadata);
        Mono<Void> create = operations.executeNative(NativeQuery.of(createDdl)).then();
        if (!options.includeIndexes()) {
            return create;
        }
        List<String> indexDdls = generator.createIndexes(metadata);
        if (indexDdls.isEmpty()) {
            return create;
        }
        return create.thenMany(Flux.fromIterable(indexDdls))
                .concatMap(ddl -> operations.executeNative(NativeQuery.of(ddl)))
                .then();
    }

    private Mono<Void> dropOne(Class<?> entityType, SchemaOptions options) {
        EntityMetadata<?> metadata = metadataFactory.getEntityMetadata(entityType);
        SchemaGenerator generator = dialect.schemaGenerator();
        String dropDdl = options.ifNotExists()
                ? generator.dropTableIfExists(metadata)
                : generator.dropTable(metadata);
        return operations.executeNative(NativeQuery.of(dropDdl)).then();
    }

    private static List<Class<?>> copyOf(Iterable<Class<?>> entityTypes) {
        List<Class<?>> list = new ArrayList<>();
        for (Class<?> type : entityTypes) {
            Objects.requireNonNull(type, "entityType must not be null");
            list.add(type);
        }
        if (list.isEmpty()) {
            throw new IllegalArgumentException("entityTypes must not be empty");
        }
        return list;
    }
}
