package io.nova.schema;

import io.nova.core.ReactiveEntityOperations;
import io.nova.metadata.CollectionTableDefinition;
import io.nova.metadata.ElementCollectionInfo;
import io.nova.metadata.EntityMetadata;
import io.nova.metadata.EntityMetadataFactory;
import io.nova.metadata.ForeignKeyDefinition;
import io.nova.metadata.JoinTableDefinition;
import io.nova.metadata.ManyToManyInfo;
import io.nova.metadata.PersistentProperty;
import io.nova.metadata.TableGeneratorInfo;
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
        // generator 테이블(@TableGenerator)을 먼저 만들고 seed → entity 테이블 → link/collection table → FK 제약 순서로 만든다.
        // FK 제약(@ForeignKey)은 모든 테이블이 존재한 뒤 마지막 phase로 발행해 forward reference를 안전하게 처리한다.
        return createTableGenerators(all, options)
                .then(Flux.fromIterable(collapseToRoots(all))
                        .concatMap(type -> createOne(type, options))
                        .then())
                .then(addOneToManyOrderColumns(all, options))
                .then(createJoinTables(all, options))
                .then(createCollectionTables(all, options))
                .then(addForeignKeys(all, options));
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
        // link/collection table을 먼저 드롭하고(참조 관계 친화) entity 테이블, 마지막으로 generator 테이블을 드롭한다.
        return dropCollectionTables(all, options)
                .then(dropJoinTables(all, options))
                .then(Flux.fromIterable(collapseToRoots(all))
                        .concatMap(type -> dropOne(type, options))
                        .then())
                .then(dropTableGenerators(all));
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
        // link/collection 드롭 → entity 드롭 → generator 테이블 드롭 → generator 테이블 생성+seed →
        // entity 생성 → link/collection 생성.
        return dropCollectionTables(all, dropOptions)
                .then(dropJoinTables(all, dropOptions))
                .then(Flux.fromIterable(reversed).concatMap(type -> dropOne(type, dropOptions)).then())
                .then(dropTableGenerators(all))
                .then(createTableGenerators(all, createOptions))
                .then(Flux.fromIterable(ordered).concatMap(type -> createOne(type, createOptions)).then())
                .then(addOneToManyOrderColumns(all, createOptions))
                .then(createJoinTables(all, createOptions))
                .then(createCollectionTables(all, createOptions))
                .then(addForeignKeys(all, createOptions));
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
        // primary 테이블에는 primary 컬럼(+ 상속 discriminator)만 기대한다. @SecondaryTable로 라우팅된 컬럼은
        // 보조 테이블 쪽에서 검증한다.
        List<String> expectedColumns = new ArrayList<>(metadata.primaryColumnMappedProperties().stream()
                .map(PersistentProperty::columnName)
                .toList());
        if (metadata.hasInheritance()) {
            expectedColumns.add(metadata.inheritance().discriminatorColumn());
        }
        Mono<String> primaryProblem = operations.queryNative(
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
        if (!metadata.hasSecondaryTables()) {
            return primaryProblem;
        }
        Mono<String> secondaryProblem = Flux.fromIterable(metadata.secondaryTables())
                .concatMap(secondary -> validateSecondaryTable(metadata, secondary, existingTables))
                .filter(problem -> !problem.isEmpty())
                .collectList()
                .map(problems -> String.join("; ", problems));
        return Mono.zip(primaryProblem, secondaryProblem, SimpleSchemaInitializer::joinProblems);
    }

    private static String joinProblems(String first, String second) {
        if (first.isEmpty()) {
            return second;
        }
        if (second.isEmpty()) {
            return first;
        }
        return first + "; " + second;
    }

    /**
     * 한 보조 테이블의 존재와 (PK 조인 컬럼 + 라우팅된 컬럼) 존재를 검증해 문제 메시지(없으면 빈 문자열)를 발행한다.
     */
    private Mono<String> validateSecondaryTable(
            EntityMetadata<?> metadata,
            io.nova.metadata.SecondaryTableInfo secondary,
            java.util.Set<String> existingTables) {
        String table = secondary.tableName();
        if (!existingTables.contains(table)) {
            return Mono.just("secondary table '" + table + "' is missing");
        }
        List<String> expectedColumns = new ArrayList<>();
        expectedColumns.add(secondary.pkJoinColumn());
        for (PersistentProperty property : metadata.secondaryColumnMappedProperties(secondary)) {
            expectedColumns.add(property.columnName());
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
                    return missing.isEmpty() ? "" : "secondary table '" + table + "' is missing columns " + missing;
                });
    }

    private Mono<Void> createOne(Class<?> entityType, SchemaOptions options) {
        EntityMetadata<?> rootMetadata = metadataFactory.getEntityMetadata(schemaRootClass(entityType));
        if (rootMetadata.hasInheritance() && rootMetadata.inheritance().joined()) {
            return createJoinedHierarchy(entityType, options);
        }
        if (rootMetadata.hasInheritance() && rootMetadata.inheritance().tablePerClass()) {
            return createTablePerClassHierarchy(entityType, options);
        }
        EntityMetadata<?> metadata = schemaMetadata(entityType);
        SchemaGenerator generator = dialect.schemaGenerator();
        String createDdl = options.ifNotExists()
                ? generator.createTableIfNotExists(metadata)
                : generator.createTable(metadata);
        Mono<Void> create = operations.executeNative(NativeQuery.of(createDdl)).then();
        if (metadata.hasSecondaryTables()) {
            // 보조 테이블은 primary 테이블 생성 이후에 만든다(보조 테이블 PK 조인 컬럼이 primary PK를 FK로 참조).
            create = create.then(Flux.fromIterable(metadata.secondaryTables())
                    .concatMap(secondary -> operations.executeNative(NativeQuery.of(options.ifNotExists()
                            ? generator.createSecondaryTableIfNotExists(metadata, secondary)
                            : generator.createSecondaryTable(metadata, secondary))))
                    .then());
        }
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
     * JOINED 계층: 루트 테이블을 먼저 만들고(공통 컬럼 + discriminator) 각 서브타입 테이블을 만든다
     * (루트 PK를 FK PK로 공유). FK 의존성상 루트가 서브타입보다 먼저 존재해야 한다.
     */
    private Mono<Void> createJoinedHierarchy(Class<?> entityType, SchemaOptions options) {
        io.nova.metadata.InheritanceLayout layout = metadataFactory.inheritanceLayout(schemaRootClass(entityType));
        SchemaGenerator generator = dialect.schemaGenerator();
        String rootDdl = generator.createJoinedRootTable(layout, options.ifNotExists());
        Mono<Void> create = operations.executeNative(NativeQuery.of(rootDdl)).then();
        return create.thenMany(Flux.fromIterable(layout.subtypes())
                        .filter(subtype -> subtype.metadata().entityType() != layout.info().root())
                        .concatMap(subtype -> operations.executeNative(NativeQuery.of(
                                generator.createJoinedSubtypeTable(layout, subtype, options.ifNotExists())))))
                .then();
    }

    /**
     * TABLE_PER_CLASS 계층: 각 구체 서브타입의 독립 테이블(모든 상속 컬럼 포함)을 만든다. 공유 테이블 없음.
     */
    private Mono<Void> createTablePerClassHierarchy(Class<?> entityType, SchemaOptions options) {
        io.nova.metadata.InheritanceLayout layout = metadataFactory.inheritanceLayout(schemaRootClass(entityType));
        SchemaGenerator generator = dialect.schemaGenerator();
        return Flux.fromIterable(layout.subtypes())
                .concatMap(subtype -> {
                    EntityMetadata<?> metadata = subtype.metadata();
                    String ddl = options.ifNotExists()
                            ? generator.createTableIfNotExists(metadata)
                            : generator.createTable(metadata);
                    return operations.executeNative(NativeQuery.of(ddl));
                })
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
     * {@code @OneToMany(mappedBy)} + {@code @OrderColumn}으로 정렬되는 컬렉션의 순서 정수 컬럼을 child 테이블에
     * {@code ALTER TABLE ... ADD COLUMN}으로 더한다. 순서 컬럼은 child 엔티티의 필드가 아니라 parent의
     * {@code @OneToMany} 매핑이 소유하므로, child 테이블 생성 후 이 단계에서 추가한다. (childTable, columnName)으로
     * dedupe해 여러 parent가 같은 child를 가리켜도 중복 추가하지 않는다. 정렬 {@code @OneToMany}가 없으면 no-op이다.
     */
    private Mono<Void> addOneToManyOrderColumns(List<Class<?>> types, SchemaOptions options) {
        SchemaGenerator generator = dialect.schemaGenerator();
        // childTable → (orderColumn 이름 → ALTER ADD COLUMN ddl). (childTable, column)으로 dedupe해
        // 여러 parent가 같은 child를 가리켜도 중복 추가하지 않는다.
        LinkedHashMap<String, LinkedHashMap<String, String>> byChildTable = new LinkedHashMap<>();
        for (Class<?> type : types) {
            EntityMetadata<?> metadata = metadataFactory.getEntityMetadata(type);
            for (PersistentProperty property : metadata.oneToManyProperties()) {
                var orderColumn = property.oneToManyOrderColumn();
                if (orderColumn == null) {
                    continue;
                }
                Class<?> childType = property.oneToManyTargetType();
                if (childType == null) {
                    continue;
                }
                EntityMetadata<?> childMetadata = metadataFactory.getEntityMetadata(childType);
                byChildTable
                        .computeIfAbsent(childMetadata.tableName(), t -> new LinkedHashMap<>())
                        .putIfAbsent(orderColumn.columnName(),
                                generator.addOneToManyOrderColumn(childMetadata, orderColumn.columnName()));
            }
        }
        if (byChildTable.isEmpty()) {
            return Mono.empty();
        }
        return Flux.fromIterable(byChildTable.entrySet())
                .concatMap(entry -> addOrderColumnsForChildTable(entry.getKey(), entry.getValue(), options))
                .then();
    }

    /**
     * 한 child 테이블에 정렬 {@code @OneToMany}의 order 컬럼들을 추가한다. {@code ifNotExists}(=={@code ddl-auto=UPDATE})
     * 모드에서는 ALTER ADD COLUMN이 PG/MySQL에서 IF NOT EXISTS를 지원하지 않으므로, 카탈로그({@link Dialect#listColumnsSql})
     * 로 기존 컬럼을 먼저 읽어 이미 존재하는 order 컬럼은 건너뛴다 — 재시작 시 "duplicate column"으로 스키마 초기화가
     * 깨지지 않도록(FK 발행과 동일한 멱등 계약).
     */
    private Mono<Void> addOrderColumnsForChildTable(
            String childTable, LinkedHashMap<String, String> ddlByColumn, SchemaOptions options) {
        if (!options.ifNotExists()) {
            // 새 스키마(fresh create / recreate의 드롭 후 재생성): 컬럼이 없는 상태이므로 무조건 발행한다.
            return emitOrderColumnDdls(ddlByColumn.values());
        }
        return operations.queryNative(
                        NativeQuery.of(dialect.listColumnsSql(childTable)),
                        row -> row.get(Dialect.COLUMN_NAME_COLUMN, String.class))
                .collect(() -> new java.util.TreeSet<String>(String.CASE_INSENSITIVE_ORDER),
                        (set, name) -> {
                            if (name != null) {
                                set.add(name);
                            }
                        })
                .flatMap(existing -> {
                    List<String> pending = new ArrayList<>();
                    for (var byColumn : ddlByColumn.entrySet()) {
                        if (!existing.contains(byColumn.getKey())) {
                            pending.add(byColumn.getValue());
                        }
                    }
                    return emitOrderColumnDdls(pending);
                });
    }

    private Mono<Void> emitOrderColumnDdls(Iterable<String> ddls) {
        return Flux.fromIterable(ddls)
                .concatMap(ddl -> operations.executeNative(NativeQuery.of(ddl)))
                .then();
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

    /**
     * 모든 테이블이 생성된 뒤 JPA {@code @ForeignKey(ConstraintMode.CONSTRAINT)} 소스 호환 FK 제약을 별도
     * {@code ALTER TABLE ... ADD CONSTRAINT} phase로 발행한다. {@code @ForeignKey} 미지정/{@code PROVIDER_DEFAULT}/
     * {@code NO_CONSTRAINT}는 FK를 만들지 않으므로(기존 동작 보존) 발행할 제약이 없으면 no-op이다. 모든 참조
     * 테이블이 이미 존재하므로 forward reference가 안전하다.
     */
    private Mono<Void> addForeignKeys(List<Class<?>> types, SchemaOptions options) {
        List<ForeignKeyDefinition> definitions = ForeignKeyConstraints.resolve(types, metadataFactory);
        if (definitions.isEmpty()) {
            return Mono.empty();
        }
        SchemaGenerator generator = dialect.schemaGenerator();
        if (!options.ifNotExists()) {
            // 새 스키마(fresh create / recreate의 드롭 후 재생성): 제약이 없는 상태이므로 무조건 발행한다.
            return emitForeignKeys(definitions, generator);
        }
        // 멱등 발행(ddl-auto=UPDATE 재시작): 이미 존재하는 FK 제약 이름을 읽어 거른다. ALTER ADD CONSTRAINT는
        // PostgreSQL/MySQL에서 IF NOT EXISTS를 지원하지 않아, 카탈로그 조회로 중복을 피해야 재기동이 멱등해진다.
        return operations.queryNative(
                        NativeQuery.of(dialect.listForeignKeyNamesSql()),
                        row -> row.get(Dialect.FOREIGN_KEY_NAME_COLUMN, String.class))
                .collect(() -> new java.util.TreeSet<String>(String.CASE_INSENSITIVE_ORDER),
                        (set, name) -> {
                            if (name != null) {
                                set.add(name);
                            }
                        })
                .flatMap(existing -> {
                    List<ForeignKeyDefinition> pending = new ArrayList<>();
                    for (ForeignKeyDefinition definition : definitions) {
                        if (!existing.contains(generator.foreignKeyName(definition))) {
                            pending.add(definition);
                        }
                    }
                    return emitForeignKeys(pending, generator);
                });
    }

    private Mono<Void> emitForeignKeys(List<ForeignKeyDefinition> definitions, SchemaGenerator generator) {
        if (definitions.isEmpty()) {
            return Mono.empty();
        }
        return Flux.fromIterable(definitions)
                .concatMap(definition -> operations.executeNative(
                        NativeQuery.of(generator.addForeignKey(definition))))
                .then();
    }

    private List<CollectionTableDefinition> collectionTableDefinitions(List<Class<?>> types) {
        LinkedHashMap<String, CollectionTableDefinition> byName = new LinkedHashMap<>();
        for (Class<?> type : types) {
            EntityMetadata<?> metadata = metadataFactory.getEntityMetadata(type);
            for (PersistentProperty property : metadata.elementCollectionProperties()) {
                ElementCollectionInfo info = property.elementCollectionInfo();
                Class<?> ownerIdType = metadata.idProperty().javaType();
                byName.putIfAbsent(info.collectionTableName(),
                        info.toCollectionTableDefinition(ownerIdType));
            }
        }
        return new ArrayList<>(byName.values());
    }

    /**
     * 주어진 엔티티들의 {@code @TableGenerator} generator 테이블을 만들고 행을 seed 한다. generator 테이블
     * 이름으로 dedupe(여러 entity가 같은 테이블 공유 가능)하되, seed 행은 generator의 (table, pkColumnValue)
     * 조합별로 한 번씩 INSERT 한다. {@code @TableGenerator}가 없으면 no-op이다.
     */
    private Mono<Void> createTableGenerators(List<Class<?>> types, SchemaOptions options) {
        List<TableGeneratorInfo> definitions = tableGeneratorDefinitions(types);
        if (definitions.isEmpty()) {
            return Mono.empty();
        }
        SchemaGenerator generator = dialect.schemaGenerator();
        // 같은 물리 테이블은 한 번만 CREATE 한다.
        LinkedHashMap<String, TableGeneratorInfo> tableByName = new LinkedHashMap<>();
        for (TableGeneratorInfo info : definitions) {
            tableByName.putIfAbsent(info.table(), info);
        }
        Mono<Void> createTables = Flux.fromIterable(tableByName.values())
                .concatMap(info -> {
                    String ddl = options.ifNotExists()
                            ? generator.createTableGeneratorIfNotExists(info)
                            : generator.createTableGenerator(info);
                    return operations.executeNative(NativeQuery.of(ddl));
                })
                .then();
        // generator 행은 (table, pkColumnValue)별로 한 번씩 seed 한다.
        Mono<Void> seedRows = Flux.fromIterable(definitions)
                .concatMap(info -> operations.executeNative(NativeQuery.of(generator.seedTableGenerator(info))))
                .then();
        return createTables.then(seedRows);
    }

    private Mono<Void> dropTableGenerators(List<Class<?>> types) {
        List<TableGeneratorInfo> definitions = tableGeneratorDefinitions(types);
        if (definitions.isEmpty()) {
            return Mono.empty();
        }
        SchemaGenerator generator = dialect.schemaGenerator();
        LinkedHashMap<String, TableGeneratorInfo> tableByName = new LinkedHashMap<>();
        for (TableGeneratorInfo info : definitions) {
            tableByName.putIfAbsent(info.table(), info);
        }
        return Flux.fromIterable(tableByName.values())
                .concatMap(info -> operations.executeNative(
                        NativeQuery.of(generator.dropTableGeneratorIfExists(info.table()))))
                .then();
    }

    /**
     * 주어진 엔티티들의 {@code @TableGenerator} 정의를 (table, pkColumnValue)별로 dedupe해 모은다.
     */
    private List<TableGeneratorInfo> tableGeneratorDefinitions(List<Class<?>> types) {
        LinkedHashMap<String, TableGeneratorInfo> byRow = new LinkedHashMap<>();
        for (Class<?> type : types) {
            EntityMetadata<?> metadata = metadataFactory.getEntityMetadata(schemaRootClass(type));
            metadata.tableGenerator().ifPresent(info ->
                    byRow.putIfAbsent(info.table() + ' ' + info.pkColumnValue(), info));
        }
        return new ArrayList<>(byRow.values());
    }

    private Mono<Void> dropOne(Class<?> entityType, SchemaOptions options) {
        EntityMetadata<?> rootMetadata = metadataFactory.getEntityMetadata(schemaRootClass(entityType));
        if (rootMetadata.hasInheritance()
                && (rootMetadata.inheritance().joined() || rootMetadata.inheritance().tablePerClass())) {
            return dropMultiTableHierarchy(entityType, options);
        }
        EntityMetadata<?> metadata = schemaMetadata(entityType);
        SchemaGenerator generator = dialect.schemaGenerator();
        String dropDdl = options.ifNotExists()
                ? generator.dropTableIfExists(metadata)
                : generator.dropTable(metadata);
        Mono<Void> dropPrimary = operations.executeNative(NativeQuery.of(dropDdl)).then();
        if (!metadata.hasSecondaryTables()) {
            return dropPrimary;
        }
        // 보조 테이블을 먼저 드롭한다(primary를 FK로 참조하므로). 그 다음 primary 테이블.
        return Flux.fromIterable(metadata.secondaryTables())
                .concatMap(secondary -> operations.executeNative(NativeQuery.of(options.ifNotExists()
                        ? generator.dropSecondaryTableIfExists(secondary)
                        : generator.dropSecondaryTable(secondary))))
                .then()
                .then(dropPrimary);
    }

    /**
     * JOINED/TABLE_PER_CLASS 계층 테이블을 드롭한다. JOINED는 서브타입 테이블을 먼저(FK 의존성), 마지막으로
     * 루트 테이블을 드롭한다. TABLE_PER_CLASS는 각 구체 테이블을 드롭한다(공유 테이블 없음).
     */
    private Mono<Void> dropMultiTableHierarchy(Class<?> entityType, SchemaOptions options) {
        io.nova.metadata.InheritanceLayout layout = metadataFactory.inheritanceLayout(schemaRootClass(entityType));
        SchemaGenerator generator = dialect.schemaGenerator();
        Mono<Void> dropSubtypes = Flux.fromIterable(layout.subtypes())
                .filter(subtype -> !(layout.info().joined()
                        && subtype.metadata().entityType() == layout.info().root()))
                .concatMap(subtype -> {
                    EntityMetadata<?> metadata = subtype.metadata();
                    String ddl = options.ifNotExists()
                            ? generator.dropTableIfExists(metadata)
                            : generator.dropTable(metadata);
                    return operations.executeNative(NativeQuery.of(ddl));
                })
                .then();
        if (!layout.info().joined()) {
            return dropSubtypes;
        }
        // JOINED 루트 테이블은 서브타입 테이블 드롭 이후 마지막에 드롭한다.
        String rootTable = layout.info().rootTableName();
        String rootDrop = options.ifNotExists()
                ? "drop table if exists " + dialect.quote(rootTable)
                : "drop table " + dialect.quote(rootTable);
        return dropSubtypes.then(operations.executeNative(NativeQuery.of(rootDrop)).then());
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
