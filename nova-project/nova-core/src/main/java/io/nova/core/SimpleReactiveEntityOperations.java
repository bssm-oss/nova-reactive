package io.nova.core;

import io.nova.annotation.GenerationType;
import io.nova.exception.OptimisticLockingFailureException;
import io.nova.metadata.EntityMetadata;
import io.nova.metadata.EntityMetadataFactory;
import io.nova.metadata.PersistentProperty;
import io.nova.query.AggregateRow;
import io.nova.query.AggregateSpec;
import io.nova.query.Aggregation;
import io.nova.query.Criteria;
import io.nova.query.NativeQuery;
import io.nova.query.Projection;
import io.nova.query.QuerySpec;
import io.nova.query.Updater;
import io.nova.sql.CompiledQuery;
import io.nova.sql.Dialect;
import io.nova.sql.SchemaGenerator;
import io.nova.sql.SqlStatement;
import io.nova.tx.ReactiveTransactionOperations;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.lang.reflect.Constructor;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * 엔티티 메타데이터, SQL 렌더러, executor를 기반으로 동작하는 기본 {@link ReactiveEntityOperations} 구현체다.
 */
public final class SimpleReactiveEntityOperations implements ReactiveEntityOperations {
    private final EntityMetadataFactory metadataFactory;
    private final Dialect dialect;
    private final SqlExecutor sqlExecutor;
    private final EntityStateDetector entityStateDetector;
    private final ReactiveTransactionOperations transactionOperations;
    private final Clock clock;
    private final AuditApplier auditApplier;
    private final EntityListenerInvoker listenerInvoker;

    public SimpleReactiveEntityOperations(
            EntityMetadataFactory metadataFactory,
            Dialect dialect,
            SqlExecutor sqlExecutor,
            EntityStateDetector entityStateDetector,
            ReactiveTransactionOperations transactionOperations
    ) {
        this(metadataFactory, dialect, sqlExecutor, entityStateDetector, transactionOperations, Clock.systemUTC());
    }

    public SimpleReactiveEntityOperations(
            EntityMetadataFactory metadataFactory,
            Dialect dialect,
            SqlExecutor sqlExecutor,
            EntityStateDetector entityStateDetector,
            ReactiveTransactionOperations transactionOperations,
            Clock clock
    ) {
        this.metadataFactory = metadataFactory;
        this.dialect = dialect;
        this.sqlExecutor = sqlExecutor;
        this.entityStateDetector = entityStateDetector;
        this.transactionOperations = transactionOperations;
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.auditApplier = new AuditApplier(this.clock);
        this.listenerInvoker = new EntityListenerInvoker();
    }

    @Override
    public <T> Mono<T> save(T entity) {
        EntityMetadata<T> metadata = metadataFactory.getEntityMetadata(entityType(entity));
        boolean isNew = entityStateDetector.isNew(entity, metadata);
        if (isNew) {
            // audit applier가 먼저 실행되어 createdAt/updatedAt에 기본값이 채워진 뒤,
            // 사용자 @PrePersist callback이 호출되어 audit 필드 포함 entity 상태를 마지막에 결정한다.
            // 콜백이 예외를 던지면 sync error가 Mono.error로 흘러가게 try/catch로 감싼다.
            try {
                auditApplier.applyOnInsert(entity, metadata);
                listenerInvoker.invokePrePersist(entity, metadata);
                initializeVersionIfAbsent(metadata, entity);
            } catch (RuntimeException exception) {
                return Mono.error(exception);
            }
            return insertNew(metadata, entity);
        }
        try {
            auditApplier.applyOnUpdate(entity, metadata);
            listenerInvoker.invokePreUpdate(entity, metadata);
        } catch (RuntimeException exception) {
            return Mono.error(exception);
        }
        return updateExisting(metadata, entity);
    }

    private <T> Mono<T> insertNew(EntityMetadata<T> metadata, T entity) {
        PersistentProperty idProperty = metadata.idProperty();
        GenerationType strategy = idProperty.generationType();
        if (strategy == GenerationType.SEQUENCE) {
            Class<?> idColumnType = wrapPrimitive(idProperty.javaType());
            return sqlExecutor.queryOne(
                            new SqlStatement(dialect.sequenceNextValueSql(idProperty.generator()), List.of()),
                            row -> row.get(Dialect.SEQUENCE_VALUE_COLUMN, idColumnType))
                    .flatMap(value -> {
                        idProperty.write(entity, idProperty.toPropertyValue(value));
                        SqlStatement statement = dialect.sqlRenderer().insert(metadata, entity);
                        return sqlExecutor.execute(statement).thenReturn(entity);
                    });
        }
        if (strategy == GenerationType.UUID) {
            assignUuidId(idProperty, entity);
            SqlStatement statement = dialect.sqlRenderer().insert(metadata, entity);
            return sqlExecutor.execute(statement).thenReturn(entity);
        }
        if (idProperty.generated()) {
            SqlStatement statement = dialect.sqlRenderer().insert(metadata, entity);
            return sqlExecutor.executeAndReturnGeneratedKey(statement, idProperty.columnName(), wrapPrimitive(idProperty.javaType()))
                    .map(key -> {
                        idProperty.write(entity, idProperty.toPropertyValue(key));
                        return entity;
                    })
                    .defaultIfEmpty(entity);
        }
        SqlStatement statement = dialect.sqlRenderer().insert(metadata, entity);
        return sqlExecutor.execute(statement).thenReturn(entity);
    }

    private static void assignUuidId(PersistentProperty idProperty, Object entity) {
        if (idProperty.read(entity) != null) {
            return;
        }
        UUID generated = UUID.randomUUID();
        Class<?> type = idProperty.javaType();
        if (type == UUID.class) {
            idProperty.write(entity, generated);
        } else if (type == String.class) {
            idProperty.write(entity, generated.toString());
        } else {
            throw new IllegalStateException("Unsupported UUID id type " + type.getName());
        }
    }

    private <T> Mono<T> updateExisting(EntityMetadata<T> metadata, T entity) {
        PersistentProperty versionProperty = metadata.versionProperty().orElse(null);
        SqlStatement statement = dialect.sqlRenderer().update(metadata, entity);
        if (versionProperty == null) {
            return sqlExecutor.execute(statement).thenReturn(entity);
        }
        Object current = versionProperty.read(entity);
        Object next = nextVersionValue(versionProperty, current);
        return sqlExecutor.execute(statement)
                .flatMap(affected -> {
                    if (affected == 0L) {
                        return Mono.error(new OptimisticLockingFailureException(
                                "Optimistic locking failure: row not found or version mismatch for "
                                        + metadata.entityType().getName()
                                        + " id=" + metadata.idProperty().read(entity)
                                        + " version=" + current));
                    }
                    versionProperty.write(entity, next);
                    return Mono.just(entity);
                });
    }

    @Override
    public <T> Mono<T> update(T entity, Iterable<String> fields) {
        Objects.requireNonNull(entity, "entity must not be null");
        Objects.requireNonNull(fields, "fields must not be null");
        EntityMetadata<T> metadata = metadataFactory.getEntityMetadata(entityType(entity));
        Object id = metadata.idProperty().read(entity);
        if (id == null) {
            return Mono.error(new IllegalArgumentException("Entity id must not be null for update"));
        }
        try {
            auditApplier.applyOnUpdate(entity, metadata);
            listenerInvoker.invokePreUpdate(entity, metadata);
        } catch (RuntimeException exception) {
            return Mono.error(exception);
        }
        Iterable<String> effectiveFields = fields;
        Optional<String> updatedAtName = auditApplier.updatedAtPropertyName(metadata);
        if (updatedAtName.isPresent()) {
            effectiveFields = augmentWithExtraField(effectiveFields, updatedAtName.get());
        }
        PersistentProperty versionProperty = metadata.versionProperty().orElse(null);
        if (versionProperty != null) {
            effectiveFields = augmentWithExtraField(effectiveFields, versionProperty.propertyName());
        }
        SqlStatement statement = dialect.sqlRenderer().update(metadata, entity, effectiveFields);
        if (versionProperty == null) {
            return sqlExecutor.execute(statement).thenReturn(entity);
        }
        Object current = versionProperty.read(entity);
        Object next = nextVersionValue(versionProperty, current);
        return sqlExecutor.execute(statement)
                .flatMap(affected -> {
                    if (affected == 0L) {
                        return Mono.error(new OptimisticLockingFailureException(
                                "Optimistic locking failure: row not found or version mismatch for "
                                        + metadata.entityType().getName()
                                        + " id=" + id
                                        + " version=" + current));
                    }
                    versionProperty.write(entity, next);
                    return Mono.just(entity);
                });
    }

    private static Iterable<String> augmentWithExtraField(Iterable<String> fields, String extraField) {
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        for (String field : fields) {
            merged.add(field);
        }
        merged.add(extraField);
        return merged;
    }

    @Override
    public <T, ID> Mono<T> findById(Class<T> entityType, ID id) {
        EntityMetadata<T> metadata = metadataFactory.getEntityMetadata(entityType);
        return sqlExecutor.queryOne(dialect.sqlRenderer().selectById(metadata, id), row -> mapRow(metadata, row));
    }

    @Override
    public <T> Flux<T> findAll(Class<T> entityType, QuerySpec querySpec) {
        EntityMetadata<T> metadata = metadataFactory.getEntityMetadata(entityType);
        return sqlExecutor.queryMany(dialect.sqlRenderer().select(metadata, normalize(querySpec)), row -> mapRow(metadata, row));
    }

    @Override
    public <E, P> Flux<P> findAll(Projection<E, P> projection, QuerySpec querySpec) {
        Objects.requireNonNull(projection, "projection must not be null");
        EntityMetadata<E> metadata = metadataFactory.getEntityMetadata(projection.entityType());
        List<String> fields = projection.fields();
        List<PersistentProperty> resolved;
        Constructor<P> constructor;
        try {
            resolved = resolveProjectionProperties(metadata, fields);
            constructor = resolveProjectionConstructor(projection.projectionType(), fields.size());
        } catch (RuntimeException exception) {
            return Flux.error(exception);
        }
        SqlStatement statement = dialect.sqlRenderer().selectProjection(metadata, fields, normalize(querySpec));
        return sqlExecutor.queryMany(statement, row -> mapProjectionRow(constructor, resolved, row));
    }

    @Override
    public <T, ID> Flux<T> findAllById(Class<T> entityType, Iterable<ID> ids) {
        List<ID> materialized = toList(ids);
        if (materialized.isEmpty()) {
            return Flux.empty();
        }
        EntityMetadata<T> metadata = metadataFactory.getEntityMetadata(entityType);
        String idPropertyName = metadata.idProperty().propertyName();
        QuerySpec spec = QuerySpec.empty().where(Criteria.in(idPropertyName, materialized));
        return sqlExecutor.queryMany(dialect.sqlRenderer().select(metadata, spec), row -> mapRow(metadata, row));
    }

    @Override
    public <T> Mono<Long> delete(T entity) {
        EntityMetadata<T> metadata = metadataFactory.getEntityMetadata(entityType(entity));
        Object id = metadata.idProperty().read(entity);
        if (id == null) {
            return Mono.error(new IllegalArgumentException("Entity id must not be null for delete"));
        }
        // soft-delete UPDATE 경로에서도 동일하게 @PreRemove를 호출해 hard/soft 차이를 콜백 관점에서는 숨긴다.
        try {
            listenerInvoker.invokePreRemove(entity, metadata);
        } catch (RuntimeException exception) {
            return Mono.error(exception);
        }
        Optional<PersistentProperty> softDelete = metadata.softDeleteProperty();
        Optional<PersistentProperty> version = metadata.versionProperty();
        if (softDelete.isPresent() && version.isPresent()) {
            Object deletedAt = currentTimeFor(softDelete.get());
            PersistentProperty versionProperty = version.get();
            Object currentVersion = versionProperty.read(entity);
            Object nextVersion = nextVersionValue(versionProperty, currentVersion);
            SqlStatement statement = dialect.sqlRenderer().softDeleteByEntity(metadata, entity, deletedAt);
            return sqlExecutor.execute(statement)
                    .flatMap(affected -> {
                        if (affected == 0L) {
                            return Mono.error(new OptimisticLockingFailureException(
                                    "Optimistic locking failure: row not found or version mismatch for "
                                            + metadata.entityType().getName()
                                            + " id=" + id
                                            + " version=" + currentVersion));
                        }
                        softDelete.get().write(entity, deletedAt);
                        versionProperty.write(entity, nextVersion);
                        return Mono.just(affected);
                    });
        }
        if (softDelete.isPresent()) {
            Object deletedAt = currentTimeFor(softDelete.get());
            softDelete.get().write(entity, deletedAt);
            return sqlExecutor.execute(dialect.sqlRenderer().softDeleteById(metadata, id, deletedAt));
        }
        if (version.isPresent()) {
            PersistentProperty versionProperty = version.get();
            Object currentVersion = versionProperty.read(entity);
            SqlStatement statement = dialect.sqlRenderer().deleteByEntity(metadata, entity);
            return sqlExecutor.execute(statement)
                    .flatMap(affected -> {
                        if (affected == 0L) {
                            return Mono.error(new OptimisticLockingFailureException(
                                    "Optimistic locking failure: row not found or version mismatch for "
                                            + metadata.entityType().getName()
                                            + " id=" + id
                                            + " version=" + currentVersion));
                        }
                        return Mono.just(affected);
                    });
        }
        return sqlExecutor.execute(dialect.sqlRenderer().deleteById(metadata, id));
    }

    @Override
    public <T, ID> Mono<Long> deleteById(Class<T> entityType, ID id) {
        Objects.requireNonNull(id, "id must not be null");
        EntityMetadata<T> metadata = metadataFactory.getEntityMetadata(entityType);
        Optional<PersistentProperty> softDelete = metadata.softDeleteProperty();
        if (softDelete.isPresent()) {
            Object deletedAt = currentTimeFor(softDelete.get());
            return sqlExecutor.execute(dialect.sqlRenderer().softDeleteById(metadata, id, deletedAt));
        }
        return sqlExecutor.execute(dialect.sqlRenderer().deleteById(metadata, id));
    }

    @Override
    public <T> Mono<Long> count(Class<T> entityType, QuerySpec querySpec) {
        EntityMetadata<T> metadata = metadataFactory.getEntityMetadata(entityType);
        return sqlExecutor.queryOne(dialect.sqlRenderer().count(metadata, normalize(querySpec)), row -> row.get("count", Long.class));
    }

    @Override
    public <T> Mono<Boolean> exists(Class<T> entityType, QuerySpec querySpec) {
        EntityMetadata<T> metadata = metadataFactory.getEntityMetadata(entityType);
        return sqlExecutor.queryOne(dialect.sqlRenderer().exists(metadata, normalize(querySpec)), row -> Boolean.TRUE)
                .defaultIfEmpty(Boolean.FALSE);
    }

    @Override
    public <R> Mono<R> inTransaction(Function<ReactiveEntityOperations, Mono<R>> callback) {
        return transactionOperations.inTransaction(ignored -> callback.apply(this));
    }

    /**
     * {@link AggregateSpec}을 dialect의 {@link io.nova.sql.SqlRenderer#aggregate}로 SQL로 변환해
     * 실행하고, 결과 row마다 {@link AggregateRow}를 발행한다.
     * <p>
     * <b>raw 타입 노출</b>: 반환된 {@link AggregateRow}의 값은 R2DBC driver가 돌려준 raw 객체를 그대로
     * 보존한다 — entity-level converter나 cast는 적용되지 않는다. 같은 집계 함수라도 dialect/컬럼 타입에
     * 따라 driver가 매핑하는 Java 타입이 다를 수 있다(예: {@code sum(integer)}이 PostgreSQL은 {@code Long},
     * MySQL은 {@code BigDecimal}). 자세한 정책은 {@link AggregateRow}의 클래스 Javadoc 참고.
     */
    @Override
    public <T> Flux<AggregateRow> aggregate(Class<T> entityType, AggregateSpec spec) {
        Objects.requireNonNull(entityType, "entityType must not be null");
        Objects.requireNonNull(spec, "spec must not be null");
        EntityMetadata<T> metadata = metadataFactory.getEntityMetadata(entityType);
        SqlStatement statement = dialect.sqlRenderer().aggregate(metadata, spec);
        List<String> columnOrder = aggregateColumnOrder(metadata, spec);
        return sqlExecutor.queryMany(statement, row -> mapAggregateRow(row, columnOrder));
    }

    /**
     * 집계 결과 row에서 읽을 컬럼 이름 순서를 만든다. group property는 SELECT 절에서 entity의
     * column name으로 alias되어 있으므로 column name 기준으로 lookup하고, 집계 컬럼은 alias로 lookup한다.
     */
    private List<String> aggregateColumnOrder(EntityMetadata<?> metadata, AggregateSpec spec) {
        List<String> order = new ArrayList<>(spec.groupBy().size() + spec.aggregations().size());
        for (String groupProperty : spec.groupBy()) {
            PersistentProperty property = findProperty(metadata, groupProperty);
            order.add(property.columnName());
        }
        for (Aggregation aggregation : spec.aggregations()) {
            order.add(aggregation.resolvedAlias());
        }
        return order;
    }

    private PersistentProperty findProperty(EntityMetadata<?> metadata, String propertyName) {
        for (PersistentProperty property : metadata.properties()) {
            if (property.propertyName().equals(propertyName)) {
                return property;
            }
        }
        throw new IllegalArgumentException(
                "Unknown property " + propertyName + " on " + metadata.entityType().getName());
    }

    private AggregateRow mapAggregateRow(RowAccessor row, List<String> columnOrder) {
        LinkedHashMap<String, Object> values = new LinkedHashMap<>();
        for (String column : columnOrder) {
            values.put(column, row.get(column, Object.class));
        }
        return new AggregateRow(values);
    }

    @Override
    public <T> Flux<T> saveAll(Iterable<T> entities) {
        List<T> materialized = toList(entities);
        if (materialized.isEmpty()) {
            return Flux.empty();
        }
        // (entityClass, isNew) 별로 그룹화한 뒤 그룹 안에서 SQL이 동일하면 batch, 아니면 단건 fallback로 처리한다.
        Map<GroupKey, List<T>> groups = new LinkedHashMap<>();
        for (T entity : materialized) {
            EntityMetadata<T> metadata = metadataFactory.getEntityMetadata(entityType(entity));
            boolean isNew = entityStateDetector.isNew(entity, metadata);
            groups.computeIfAbsent(new GroupKey(entity.getClass(), isNew), ignored -> new ArrayList<>()).add(entity);
        }
        return Flux.fromIterable(groups.entrySet())
                .concatMap(entry -> saveGroup(entry.getKey(), entry.getValue()));
    }

    @Override
    public <T> Mono<Long> deleteAll(Iterable<T> entities) {
        List<T> materialized = toList(entities);
        if (materialized.isEmpty()) {
            return Mono.just(0L);
        }
        Map<Class<?>, List<Object>> idsByType = new LinkedHashMap<>();
        Map<Class<?>, List<T>> entitiesByType = new LinkedHashMap<>();
        for (int i = 0; i < materialized.size(); i++) {
            T entity = materialized.get(i);
            EntityMetadata<T> metadata = metadataFactory.getEntityMetadata(entityType(entity));
            Object id = metadata.idProperty().read(entity);
            if (id == null) {
                int index = i;
                return Mono.error(new IllegalArgumentException(
                        "Entity at index " + index + " of type " + entity.getClass().getName()
                                + " has null id; deleteAll requires non-null ids"));
            }
            idsByType.computeIfAbsent(entity.getClass(), ignored -> new ArrayList<>()).add(id);
            entitiesByType.computeIfAbsent(entity.getClass(), ignored -> new ArrayList<>()).add(entity);
        }
        return Flux.fromIterable(idsByType.entrySet())
                .concatMap(entry -> {
                    EntityMetadata<?> metadata = metadataFactory.getEntityMetadata(entry.getKey());
                    Optional<PersistentProperty> softDelete = metadata.softDeleteProperty();
                    Optional<PersistentProperty> version = metadata.versionProperty();
                    if (softDelete.isPresent() && version.isPresent()) {
                        // @SoftDelete + @Version: 단일 IN-UPDATE로 묶으면 entity별 version 검증이 불가능하므로
                        // 안전하게 단건 delete(entity) 경로로 폴백한다. @PreRemove는 단건 delete가 호출한다.
                        return Flux.fromIterable(entitiesByType.get(entry.getKey()))
                                .concatMap(this::delete)
                                .reduce(0L, Long::sum);
                    }
                    // entity 인스턴스가 있는 batch 경로에서는 @PreRemove를 SQL 발화 직전에 entity 순서대로 호출한다.
                    for (T entity : entitiesByType.get(entry.getKey())) {
                        listenerInvoker.invokePreRemove(entity, metadata);
                    }
                    if (softDelete.isPresent()) {
                        Object deletedAt = currentTimeFor(softDelete.get());
                        for (T entity : entitiesByType.get(entry.getKey())) {
                            softDelete.get().write(entity, deletedAt);
                        }
                        return sqlExecutor.execute(
                                dialect.sqlRenderer().softDeleteByIds(metadata, entry.getValue(), deletedAt));
                    }
                    return sqlExecutor.execute(dialect.sqlRenderer().deleteByIds(metadata, entry.getValue()));
                })
                .reduce(0L, Long::sum);
    }

    @Override
    public <T> Mono<Long> deleteAll(Class<T> entityType, QuerySpec querySpec) {
        EntityMetadata<T> metadata = metadataFactory.getEntityMetadata(entityType);
        Optional<PersistentProperty> softDelete = metadata.softDeleteProperty();
        if (softDelete.isPresent()) {
            Object deletedAt = currentTimeFor(softDelete.get());
            return sqlExecutor.execute(dialect.sqlRenderer().softDeleteByQuery(metadata, querySpec, deletedAt));
        }
        return sqlExecutor.execute(dialect.sqlRenderer().deleteByQuery(metadata, querySpec));
    }

    @Override
    public <T> Mono<Long> update(Class<T> entityType, Updater<T> updater) {
        Objects.requireNonNull(entityType, "entityType must not be null");
        Objects.requireNonNull(updater, "updater must not be null");
        if (!entityType.equals(updater.entityType())) {
            return Mono.error(new IllegalArgumentException(
                    "Updater entity type " + updater.entityType().getName()
                            + " does not match call entityType " + entityType.getName()));
        }
        LinkedHashMap<String, Object> fieldValues = updater.fields();
        if (fieldValues.isEmpty()) {
            return Mono.error(new IllegalArgumentException("Updater requires at least one set(...) assignment"));
        }
        if (updater.where() == null) {
            return Mono.error(new IllegalArgumentException("Updater requires a where(...) predicate"));
        }
        EntityMetadata<T> metadata = metadataFactory.getEntityMetadata(entityType);
        auditApplier.applyOnUpdaterFields(fieldValues, metadata);
        QuerySpec querySpec = QuerySpec.empty().where(updater.where());
        return Mono.fromCallable(() -> dialect.sqlRenderer().updateByQuery(metadata, fieldValues, querySpec))
                .flatMap(sqlExecutor::execute);
    }

    @Override
    public <T> Flux<T> findAll(Class<T> entityType, CompiledQuery query, Object... bindings) {
        Objects.requireNonNull(entityType, "entityType must not be null");
        Objects.requireNonNull(query, "query must not be null");
        Objects.requireNonNull(bindings, "bindings must not be null");
        SqlStatement statement;
        try {
            statement = query.bind(bindings);
        } catch (RuntimeException exception) {
            return Flux.error(exception);
        }
        EntityMetadata<T> metadata = metadataFactory.getEntityMetadata(entityType);
        return sqlExecutor.queryMany(statement, row -> mapRow(metadata, row));
    }

    @Override
    public Mono<Long> execute(CompiledQuery query, Object... bindings) {
        Objects.requireNonNull(query, "query must not be null");
        Objects.requireNonNull(bindings, "bindings must not be null");
        SqlStatement statement;
        try {
            statement = query.bind(bindings);
        } catch (RuntimeException exception) {
            return Mono.error(exception);
        }
        return sqlExecutor.execute(statement);
    }

    @Override
    public Mono<Long> executeNative(NativeQuery query) {
        Objects.requireNonNull(query, "query must not be null");
        return sqlExecutor.execute(new SqlStatement(query.sql(), query.bindings()));
    }

    @Override
    public <T> Flux<T> queryNative(NativeQuery query, Function<RowAccessor, T> mapper) {
        Objects.requireNonNull(query, "query must not be null");
        Objects.requireNonNull(mapper, "mapper must not be null");
        return sqlExecutor.queryMany(new SqlStatement(query.sql(), query.bindings()), mapper);
    }

    @Override
    public <T> Mono<T> queryNativeOne(NativeQuery query, Function<RowAccessor, T> mapper) {
        Objects.requireNonNull(query, "query must not be null");
        Objects.requireNonNull(mapper, "mapper must not be null");
        return sqlExecutor.queryOne(new SqlStatement(query.sql(), query.bindings()), mapper);
    }

    @Override
    public <T, ID> Mono<Long> deleteAllById(Class<T> entityType, Iterable<ID> ids) {
        List<ID> materialized = toList(ids);
        if (materialized.isEmpty()) {
            return Mono.just(0L);
        }
        List<Object> idValues = new ArrayList<>(materialized.size());
        for (int i = 0; i < materialized.size(); i++) {
            ID id = materialized.get(i);
            if (id == null) {
                int index = i;
                return Mono.error(new IllegalArgumentException(
                        "Id at index " + index + " for type " + entityType.getName()
                                + " is null; deleteAllById requires non-null ids"));
            }
            idValues.add(id);
        }
        EntityMetadata<T> metadata = metadataFactory.getEntityMetadata(entityType);
        Optional<PersistentProperty> softDelete = metadata.softDeleteProperty();
        if (softDelete.isPresent()) {
            Object deletedAt = currentTimeFor(softDelete.get());
            return sqlExecutor.execute(dialect.sqlRenderer().softDeleteByIds(metadata, idValues, deletedAt));
        }
        return sqlExecutor.execute(dialect.sqlRenderer().deleteByIds(metadata, idValues));
    }

    /**
     * 같은 (entityType, isNew) 그룹에서 SqlRenderer가 만드는 SQL이 동일하면 binding의
     * size·타입 순서도 동일하다는 EntityMetadata contract에 의존한다. 동일성 위반은 fallback로
     * 단건 처리한다. {@code @Version}이 선언된 엔티티의 update 그룹은 affected-rows 단건 검증이
     * 필요하므로 batch 경로를 우회해 {@link #save(Object)}로 폴백한다.
     */
    private <T> Flux<T> saveGroup(GroupKey key, List<T> entities) {
        @SuppressWarnings("unchecked")
        EntityMetadata<T> metadata = (EntityMetadata<T>) metadataFactory.getEntityMetadata(key.entityClass());
        if (!key.isNew() && metadata.versionProperty().isPresent()) {
            // optimistic locking은 entity별 affected rows 검증이 필요해 batch 경로로 묶을 수 없다.
            return Flux.fromIterable(entities).concatMap(this::save);
        }
        if (key.isNew() && metadata.idProperty().generationType() == GenerationType.SEQUENCE) {
            // SEQUENCE는 엔티티별 nextval 조회가 필요해 batch로 묶을 수 없다.
            return Flux.fromIterable(entities).concatMap(this::save);
        }
        if (key.isNew() && metadata.idProperty().generationType() == GenerationType.UUID) {
            // UUID는 batch 이전에 모든 엔티티에 식별자를 찍어두면 동일 SQL 셰이프로 batch가 가능하다.
            PersistentProperty idProperty = metadata.idProperty();
            for (T entity : entities) {
                assignUuidId(idProperty, entity);
            }
        }
        if (key.isNew() && metadata.versionProperty().isPresent()) {
            // insert 전에 각 엔티티의 version을 0으로 초기화한다. 배치 SQL과 일관되게 binding하기 위해.
            for (T entity : entities) {
                initializeVersionIfAbsent(metadata, entity);
            }
        }
        List<SqlStatement> statements = new ArrayList<>(entities.size());
        String sharedSql = null;
        int sharedBindingsSize = -1;
        boolean uniformShape = true;
        for (T entity : entities) {
            if (key.isNew()) {
                auditApplier.applyOnInsert(entity, metadata);
                listenerInvoker.invokePrePersist(entity, metadata);
            } else {
                auditApplier.applyOnUpdate(entity, metadata);
                listenerInvoker.invokePreUpdate(entity, metadata);
            }
            SqlStatement statement = key.isNew()
                    ? dialect.sqlRenderer().insert(metadata, entity)
                    : dialect.sqlRenderer().update(metadata, entity);
            statements.add(statement);
            if (sharedSql == null) {
                sharedSql = statement.sql();
                sharedBindingsSize = statement.bindings().size();
            } else if (!sharedSql.equals(statement.sql()) || sharedBindingsSize != statement.bindings().size()) {
                uniformShape = false;
            }
        }
        if (!uniformShape) {
            // SQL 셰이프 혹은 binding 개수가 그룹 안에서 다르면 안전하게 단건 fallback로 처리한다.
            return Flux.fromIterable(statements)
                    .concatMap(sqlExecutor::execute)
                    .thenMany(Flux.fromIterable(entities));
        }
        List<List<Object>> bindingsList = new ArrayList<>(statements.size());
        for (SqlStatement statement : statements) {
            bindingsList.add(statement.bindings());
        }
        if (key.isNew() && EntityMetadata.isDatabaseGeneratedId(metadata.idProperty())) {
            // generated id 그룹은 batch 결과로 키를 회수해 각 entity에 순서대로 주입한다.
            // emit된 key를 모두 모아 entity 개수와 비교하고, 한 건이라도 누락되면 fail-fast 한다.
            // driver/dialect 차이로 키가 부족하거나 초과하면 null id leak이나 silent drop 대신
            // IllegalStateException으로 즉시 노출해야 한다.
            PersistentProperty idProperty = metadata.idProperty();
            String finalSharedSql = sharedSql;
            return Flux.defer(() -> sqlExecutor
                    .executeBatchAndReturnGeneratedKeys(
                            finalSharedSql, bindingsList, idProperty.columnName(), wrapPrimitive(idProperty.javaType()))
                    .collectList()
                    .flatMapMany(collectedKeys -> {
                        if (collectedKeys.size() != entities.size()) {
                            return Flux.error(new IllegalStateException(
                                    "Batch generated keys count " + collectedKeys.size()
                                            + " != entities count " + entities.size()
                                            + " for " + finalSharedSql));
                        }
                        for (int i = 0; i < entities.size(); i++) {
                            idProperty.write(entities.get(i), idProperty.toPropertyValue(collectedKeys.get(i)));
                        }
                        return Flux.fromIterable(entities);
                    }));
        }
        return sqlExecutor.executeBatch(sharedSql, bindingsList)
                .thenMany(Flux.fromIterable(entities));
    }

    private static <T> List<T> toList(Iterable<T> iterable) {
        Objects.requireNonNull(iterable, "iterable must not be null");
        if (iterable instanceof List<T> list) {
            return list;
        }
        List<T> result = new ArrayList<>();
        for (T item : iterable) {
            result.add(item);
        }
        return result;
    }

    private record GroupKey(Class<?> entityClass, boolean isNew) {
    }

    /**
     * 현재 dialect를 사용해 주어진 엔티티 타입의 단일 테이블 생성 구문을 만든다.
     */
    public String createTableSql(Class<?> entityType) {
        EntityMetadata<?> metadata = metadataFactory.getEntityMetadata(entityType);
        SchemaGenerator schemaGenerator = dialect.schemaGenerator();
        return schemaGenerator.createTable(metadata);
    }

    private QuerySpec normalize(QuerySpec querySpec) {
        return querySpec == null ? QuerySpec.empty() : querySpec;
    }

    private <T> T mapRow(EntityMetadata<T> metadata, RowAccessor row) {
        T instance = instantiate(metadata.entityType());
        // embedded(host field가 동일한) sub-property들을 한 번에 모아 hydration한다.
        // 같은 host group에 속한 모든 sub-column이 NULL이면 host 필드 자체를 null로 둔다.
        LinkedHashMap<java.lang.reflect.Field, List<EmbeddedValue>> embeddedBuckets = new LinkedHashMap<>();
        for (PersistentProperty property : metadata.properties()) {
            // primitive Java 타입을 그대로 row.get(..., type)에 넘기면 일부 R2DBC driver(예: r2dbc-h2)가
            // "Cannot decode value of type boolean/long/..."으로 거부하므로 boxed wrapper로 변환한다.
            // entity 필드 주입 시점에는 reflection이 boxed → primitive unboxing을 자동 처리한다.
            Object raw = row.get(property.columnName(), wrapPrimitive(property.javaType()));
            Object value = property.toPropertyValue(raw);
            if (property.embedded()) {
                embeddedBuckets
                        .computeIfAbsent(property.embeddedHostField(), ignored -> new ArrayList<>())
                        .add(new EmbeddedValue(property, value));
                continue;
            }
            property.write(instance, value);
        }
        for (Map.Entry<java.lang.reflect.Field, List<EmbeddedValue>> entry : embeddedBuckets.entrySet()) {
            List<EmbeddedValue> values = entry.getValue();
            boolean allNull = true;
            for (EmbeddedValue ev : values) {
                if (ev.value() != null) {
                    allNull = false;
                    break;
                }
            }
            if (allNull) {
                // host field에 직접 null을 쓴다. 빈 embeddable 인스턴스가 남지 않도록.
                try {
                    java.lang.reflect.Field host = entry.getKey();
                    host.setAccessible(true);
                    host.set(instance, null);
                } catch (IllegalAccessException exception) {
                    throw new IllegalStateException(
                            "Cannot null out embedded host field " + entry.getKey().getName(), exception);
                }
                continue;
            }
            for (EmbeddedValue ev : values) {
                ev.property().write(instance, ev.value());
            }
        }
        // hydration이 모두 끝난 다음 한 번만 @PostLoad를 발화해, 사용자 callback에서 다른 필드를 같이 읽을 수 있게 한다.
        listenerInvoker.invokePostLoad(instance, metadata);
        return instance;
    }

    private record EmbeddedValue(PersistentProperty property, Object value) {
    }

    /**
     * projection이 요청한 entity property 이름들을 메타데이터에서 찾아 정렬된 리스트로 반환한다.
     * 미존재 property는 {@link IllegalArgumentException}으로 거부된다.
     */
    private List<PersistentProperty> resolveProjectionProperties(EntityMetadata<?> metadata, List<String> fields) {
        List<PersistentProperty> resolved = new ArrayList<>(fields.size());
        for (String fieldName : fields) {
            PersistentProperty property = metadata.findProperty(fieldName)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Unknown property " + fieldName + " on " + metadata.entityType().getName()));
            resolved.add(property);
        }
        return resolved;
    }

    /**
     * projection 타입의 1-생성자를 찾는다. record는 canonical constructor를, 일반 class는 명시적
     * public/declared 단일 생성자를 사용한다. 생성자 파라미터 개수가 필드 개수와 다르면 거부된다.
     */
    @SuppressWarnings("unchecked")
    private <P> Constructor<P> resolveProjectionConstructor(Class<P> projectionType, int fieldCount) {
        Constructor<?> selected;
        if (projectionType.isRecord()) {
            Class<?>[] componentTypes = new Class<?>[projectionType.getRecordComponents().length];
            for (int i = 0; i < componentTypes.length; i++) {
                componentTypes[i] = projectionType.getRecordComponents()[i].getType();
            }
            try {
                selected = projectionType.getDeclaredConstructor(componentTypes);
            } catch (NoSuchMethodException exception) {
                throw new IllegalStateException(
                        "Projection record " + projectionType.getName() + " does not expose its canonical constructor",
                        exception);
            }
        } else {
            Constructor<?>[] constructors = projectionType.getDeclaredConstructors();
            if (constructors.length != 1) {
                throw new IllegalArgumentException(
                        "Projection type " + projectionType.getName()
                                + " must declare exactly one constructor but found " + constructors.length);
            }
            selected = constructors[0];
        }
        if (selected.getParameterCount() != fieldCount) {
            throw new IllegalArgumentException(
                    "Projection type " + projectionType.getName()
                            + " constructor expects " + selected.getParameterCount()
                            + " parameters but " + fieldCount + " fields were requested");
        }
        selected.setAccessible(true);
        return (Constructor<P>) selected;
    }

    private <P> P mapProjectionRow(
            Constructor<P> constructor,
            List<PersistentProperty> properties,
            RowAccessor row
    ) {
        Object[] arguments = new Object[properties.size()];
        for (int i = 0; i < properties.size(); i++) {
            PersistentProperty property = properties.get(i);
            // primitive 타입은 driver 호환을 위해 boxed wrapper로 변환해서 row.get에 전달한다.
            // 자세한 사유는 mapRow의 동일 주석 참고.
            Object raw = row.get(property.columnName(), wrapPrimitive(property.javaType()));
            arguments[i] = property.toPropertyValue(raw);
        }
        try {
            return constructor.newInstance(arguments);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(
                    "Cannot instantiate projection " + constructor.getDeclaringClass().getName(), exception);
        }
    }

    private <T> T instantiate(Class<T> entityType) {
        try {
            Constructor<T> constructor = entityType.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Entity type must expose a no-args constructor: " + entityType.getName(), exception);
        }
    }

    @SuppressWarnings("unchecked")
    private <T> Class<T> entityType(T entity) {
        return (Class<T>) entity.getClass();
    }

    /**
     * {@code @SoftDelete} 컬럼 타입에 맞춰 현재 시각을 만든다. 지원 타입은
     * {@link Instant}, {@link LocalDateTime}, {@link OffsetDateTime}이며,
     * factory에서 이미 타입 검증을 했으므로 다른 타입이 도달하면 metadata 오류로 본다.
     */
    private Object currentTimeFor(PersistentProperty softDeleteProperty) {
        Instant now = clock.instant();
        Class<?> type = softDeleteProperty.javaType();
        if (type == Instant.class) {
            return now;
        }
        if (type == LocalDateTime.class) {
            return LocalDateTime.ofInstant(now, clock.getZone());
        }
        if (type == OffsetDateTime.class) {
            return OffsetDateTime.ofInstant(now, clock.getZone());
        }
        throw new IllegalStateException("Unsupported @SoftDelete type " + type.getName());
    }

    /**
     * insert 시점에 {@code @Version} 필드가 비어 있으면 0으로 초기화한다. 이미 값이 있다면 호출자가
     * 명시적으로 지정한 것으로 보고 보존한다.
     */
    private void initializeVersionIfAbsent(EntityMetadata<?> metadata, Object entity) {
        metadata.versionProperty().ifPresent(versionProperty -> {
            if (versionProperty.read(entity) == null) {
                versionProperty.write(entity, zeroVersionValue(versionProperty));
            }
        });
    }

    private Object zeroVersionValue(PersistentProperty versionProperty) {
        Class<?> type = versionProperty.javaType();
        if (type == Long.class) {
            return 0L;
        }
        if (type == Integer.class) {
            return 0;
        }
        if (type == Short.class) {
            return (short) 0;
        }
        throw new IllegalStateException("Unsupported version type " + type.getName());
    }

    /**
     * Java primitive class를 대응하는 wrapper class로 변환한다. 일부 R2DBC driver(r2dbc-h2 1.0.0)는
     * {@code row.get(name, boolean.class)}처럼 primitive class를 받으면
     * {@code IllegalArgumentException: Cannot decode value of type boolean}을 던지므로,
     * row 디코딩과 generated key 회수 경로에서 항상 boxed class를 사용해 driver 호환을 보장한다.
     * <p>
     * primitive가 아니면 입력을 그대로 반환한다. boxed → primitive 변환은 reflection
     * {@code Field.set}가 자동으로 처리하므로 entity 필드 주입에 영향이 없다.
     */
    static Class<?> wrapPrimitive(Class<?> type) {
        if (!type.isPrimitive()) {
            return type;
        }
        if (type == boolean.class) return Boolean.class;
        if (type == long.class) return Long.class;
        if (type == int.class) return Integer.class;
        if (type == double.class) return Double.class;
        if (type == float.class) return Float.class;
        if (type == short.class) return Short.class;
        if (type == byte.class) return Byte.class;
        if (type == char.class) return Character.class;
        return type;
    }

    private Object nextVersionValue(PersistentProperty versionProperty, Object current) {
        Class<?> type = versionProperty.javaType();
        if (type == Long.class) {
            long value = current == null ? 0L : ((Number) current).longValue();
            return value + 1L;
        }
        if (type == Integer.class) {
            int value = current == null ? 0 : ((Number) current).intValue();
            return value + 1;
        }
        if (type == Short.class) {
            short value = current == null ? (short) 0 : ((Number) current).shortValue();
            return (short) (value + 1);
        }
        throw new IllegalStateException("Unsupported version type " + type.getName());
    }
}
