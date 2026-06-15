package io.nova.schema;

import io.nova.core.ReactiveEntityOperations;
import io.nova.metadata.CollectionTableDefinition;
import io.nova.metadata.ElementCollectionInfo;
import io.nova.metadata.EntityMetadata;
import io.nova.metadata.EntityMetadataFactory;
import io.nova.metadata.JoinTableDefinition;
import io.nova.metadata.ManyToManyInfo;
import io.nova.metadata.PersistentProperty;
import io.nova.query.NativeQuery;
import io.nova.sql.Dialect;
import io.nova.sql.SchemaGenerator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.LinkedHashMap;
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
        // Iterable 경로로 위임해 @ManyToMany link table 생성을 동일하게 처리한다.
        return create(List.of(entityType), options);
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
        List<Class<?>> all = copyOf(entityTypes);
        // entity 테이블을 먼저 만들고, link table(@ManyToMany)과 collection table(@ElementCollection)을 뒤에 만든다.
        return Flux.fromIterable(collapseToRoots(all))
                .concatMap(type -> createOne(type, options))
                .then(createJoinTables(all, options))
                .then(createCollectionTables(all, options));
    }

    @Override
    public Mono<Void> drop(Class<?> entityType) {
        return drop(entityType, SchemaOptions.defaults());
    }

    @Override
    public Mono<Void> drop(Class<?> entityType, SchemaOptions options) {
        Objects.requireNonNull(entityType, "entityType must not be null");
        return drop(List.of(entityType), options);
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
        List<Class<?>> all = copyOf(entityTypes);
        // link/collection table을 먼저 드롭하고(참조 관계 친화) entity 테이블을 뒤에 드롭한다.
        return dropCollectionTables(all, options)
                .then(dropJoinTables(all, options))
                .then(Flux.fromIterable(collapseToRoots(all))
                        .concatMap(type -> dropOne(type, options))
                        .then());
    }

    @Override
    public Mono<Void> recreate(Class<?> entityType) {
        Objects.requireNonNull(entityType, "entityType must not be null");
        return recreate(List.of(entityType));
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
        List<Class<?>> all = copyOf(entityTypes);
        List<Class<?>> ordered = collapseToRoots(all);
        List<Class<?>> reversed = new ArrayList<>(ordered);
        java.util.Collections.reverse(reversed);
        SchemaOptions dropOptions = SchemaOptions.defaults().withIfNotExists(true);
        SchemaOptions createOptions = SchemaOptions.defaults().withIfNotExists(false);
        // link/collection table 먼저 드롭 → entity 드롭 → entity 생성 → link/collection table 생성.
        return dropCollectionTables(all, dropOptions)
                .then(dropJoinTables(all, dropOptions))
                .then(Flux.fromIterable(reversed).concatMap(type -> dropOne(type, dropOptions)).then())
                .then(Flux.fromIterable(ordered).concatMap(type -> createOne(type, createOptions)).then())
                .then(createJoinTables(all, createOptions))
                .then(createCollectionTables(all, createOptions));
    }

    @Override
    public Mono<Void> validate(Iterable<Class<?>> entityTypes) {
        Objects.requireNonNull(entityTypes, "entityTypes must not be null");
        List<Class<?>> ordered = collapseToRoots(copyOf(entityTypes));
        return operations.queryNative(
                        NativeQuery.of(dialect.listTablesSql()),
                        row -> row.get(Dialect.TABLE_NAME_COLUMN, String.class))
                // case-insensitive set so dialect identifier case-folding does not cause false negatives.
                .collect(() -> new java.util.TreeSet<String>(String.CASE_INSENSITIVE_ORDER),
                        java.util.TreeSet::add)
                .flatMapMany(existingTables -> Flux.fromIterable(ordered)
                        .concatMap(type -> validateOne(type, existingTables)))
                .filter(problem -> !problem.isEmpty())
                .collectList()
                .flatMap(problems -> problems.isEmpty()
                        ? Mono.empty()
                        : Mono.error(new IllegalStateException(
                                "Schema validation failed (nova.ddl-auto=validate): " + String.join("; ", problems))))
                .then();
    }

    /**
     * 한 엔티티의 테이블 존재와 컬럼 존재를 검증해 문제 메시지(없으면 빈 문자열)를 발행한다.
     */
    private Mono<String> validateOne(Class<?> type, java.util.Set<String> existingTables) {
        EntityMetadata<?> metadata = schemaMetadata(type);
        String table = metadata.tableName();
        if (!existingTables.contains(table)) {
            return Mono.just("table '" + table + "' is missing");
        }
        List<String> expectedColumns = new ArrayList<>(metadata.columnMappedProperties().stream()
                .map(PersistentProperty::columnName)
                .toList());
        if (metadata.hasInheritance()) {
            expectedColumns.add(metadata.inheritance().discriminatorColumn());
        }
        return operations.queryNative(
                        NativeQuery.of(dialect.listColumnsSql(table)),
                        row -> row.get(Dialect.COLUMN_NAME_COLUMN, String.class))
                .collect(() -> new java.util.TreeSet<String>(String.CASE_INSENSITIVE_ORDER),
                        java.util.TreeSet::add)
                .map(actualColumns -> {
                    List<String> missing = expectedColumns.stream()
                            .filter(column -> !actualColumns.contains(column))
                            .toList();
                    return missing.isEmpty() ? "" : "table '" + table + "' is missing columns " + missing;
                });
    }

    private Mono<Void> createOne(Class<?> entityType, SchemaOptions options) {
        EntityMetadata<?> metadata = schemaMetadata(entityType);
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

    /**
     * 주어진 엔티티들의 owning {@code @ManyToMany} link table을 생성한다. tableName으로 dedupe하며,
     * owning M2M가 없으면 no-op이다.
     */
    private Mono<Void> createJoinTables(List<Class<?>> types, SchemaOptions options) {
        List<JoinTableDefinition> definitions = joinTableDefinitions(types);
        if (definitions.isEmpty()) {
            return Mono.empty();
        }
        SchemaGenerator generator = dialect.schemaGenerator();
        return Flux.fromIterable(definitions)
                .concatMap(definition -> {
                    String ddl = options.ifNotExists()
                            ? generator.createJoinTableIfNotExists(definition)
                            : generator.createJoinTable(definition);
                    return operations.executeNative(NativeQuery.of(ddl));
                })
                .then();
    }

    private Mono<Void> dropJoinTables(List<Class<?>> types, SchemaOptions options) {
        List<JoinTableDefinition> definitions = joinTableDefinitions(types);
        if (definitions.isEmpty()) {
            return Mono.empty();
        }
        SchemaGenerator generator = dialect.schemaGenerator();
        return Flux.fromIterable(definitions)
                .concatMap(definition -> {
                    String ddl = options.ifNotExists()
                            ? generator.dropJoinTableIfExists(definition.tableName())
                            : generator.dropJoinTable(definition.tableName());
                    return operations.executeNative(NativeQuery.of(ddl));
                })
                .then();
    }

    /**
     * owning {@code @ManyToMany} property들로부터 {@link JoinTableDefinition}을 만든다. FK 컬럼 타입은
     * owner/target 엔티티의 {@code @Id} Java 타입에서 온다. tableName으로 dedupe(LinkedHashMap)해 inverse가
     * 같은 물리 테이블을 중복 생성하지 않게 한다.
     */
    private List<JoinTableDefinition> joinTableDefinitions(List<Class<?>> types) {
        LinkedHashMap<String, JoinTableDefinition> byName = new LinkedHashMap<>();
        for (Class<?> type : types) {
            EntityMetadata<?> metadata = metadataFactory.getEntityMetadata(type);
            for (PersistentProperty property : metadata.manyToManyProperties()) {
                ManyToManyInfo info = property.manyToManyInfo();
                if (!info.owning()) {
                    continue;
                }
                Class<?> ownerIdType = metadata.idProperty().javaType();
                Class<?> targetIdType = metadataFactory.getEntityMetadata(info.targetType()).idProperty().javaType();
                byName.putIfAbsent(info.joinTableName(), new JoinTableDefinition(
                        info.joinTableName(),
                        info.ownerForeignKeyColumn(),
                        ownerIdType,
                        info.targetForeignKeyColumn(),
                        targetIdType));
            }
        }
        return new ArrayList<>(byName.values());
    }

    /**
     * 주어진 엔티티들의 {@code @ElementCollection} collection table을 생성한다. tableName으로 dedupe하며,
     * 값 컬렉션이 없으면 no-op이다.
     */
    private Mono<Void> createCollectionTables(List<Class<?>> types, SchemaOptions options) {
        List<CollectionTableDefinition> definitions = collectionTableDefinitions(types);
        if (definitions.isEmpty()) {
            return Mono.empty();
        }
        SchemaGenerator generator = dialect.schemaGenerator();
        return Flux.fromIterable(definitions)
                .concatMap(definition -> {
                    String ddl = options.ifNotExists()
                            ? generator.createCollectionTableIfNotExists(definition)
                            : generator.createCollectionTable(definition);
                    return operations.executeNative(NativeQuery.of(ddl));
                })
                .then();
    }

    private Mono<Void> dropCollectionTables(List<Class<?>> types, SchemaOptions options) {
        List<CollectionTableDefinition> definitions = collectionTableDefinitions(types);
        if (definitions.isEmpty()) {
            return Mono.empty();
        }
        SchemaGenerator generator = dialect.schemaGenerator();
        return Flux.fromIterable(definitions)
                .concatMap(definition -> {
                    String ddl = options.ifNotExists()
                            ? generator.dropJoinTableIfExists(definition.tableName())
                            : generator.dropJoinTable(definition.tableName());
                    return operations.executeNative(NativeQuery.of(ddl));
                })
                .then();
    }

    private List<CollectionTableDefinition> collectionTableDefinitions(List<Class<?>> types) {
        LinkedHashMap<String, CollectionTableDefinition> byName = new LinkedHashMap<>();
        for (Class<?> type : types) {
            EntityMetadata<?> metadata = metadataFactory.getEntityMetadata(type);
            for (PersistentProperty property : metadata.elementCollectionProperties()) {
                ElementCollectionInfo info = property.elementCollectionInfo();
                Class<?> ownerIdType = metadata.idProperty().javaType();
                byName.putIfAbsent(info.collectionTableName(), new CollectionTableDefinition(
                        info.collectionTableName(),
                        info.ownerForeignKeyColumn(),
                        ownerIdType,
                        info.valueColumn(),
                        info.valueType()));
            }
        }
        return new ArrayList<>(byName.values());
    }

    private Mono<Void> dropOne(Class<?> entityType, SchemaOptions options) {
        EntityMetadata<?> metadata = schemaMetadata(entityType);
        SchemaGenerator generator = dialect.schemaGenerator();
        String dropDdl = options.ifNotExists()
                ? generator.dropTableIfExists(metadata)
                : generator.dropTable(metadata);
        return operations.executeNative(NativeQuery.of(dropDdl)).then();
    }

    /**
     * SINGLE_TABLE 상속 멤버를 자신의 계층 루트로 접고(collapse) 중복을 제거해, 한 물리 테이블이 정확히
     * 한 번만 생성/삭제/검증되도록 한다. 상속이 아닌 엔티티는 그대로 둔다. 입력 순서는 보존한다.
     */
    private List<Class<?>> collapseToRoots(List<Class<?>> entityTypes) {
        List<Class<?>> result = new ArrayList<>(entityTypes.size());
        for (Class<?> type : entityTypes) {
            Class<?> root = schemaRootClass(type);
            if (!result.contains(root)) {
                result.add(root);
            }
        }
        return result;
    }

    private Class<?> schemaRootClass(Class<?> type) {
        EntityMetadata<?> metadata = metadataFactory.getEntityMetadata(type);
        return metadata.hasInheritance() ? metadata.inheritance().root() : type;
    }

    /**
     * DDL/검증에 쓸 메타데이터를 해석한다. 계층 루트면 전 서브타입 컬럼을 union한 병합 메타데이터를,
     * 그 외에는 일반 메타데이터를 반환한다.
     */
    private EntityMetadata<?> schemaMetadata(Class<?> type) {
        return metadataFactory.mergedHierarchyMetadata(schemaRootClass(type));
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
