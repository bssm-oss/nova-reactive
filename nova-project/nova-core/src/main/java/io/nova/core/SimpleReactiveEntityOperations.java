package io.nova.core;

import jakarta.persistence.EnumType;
import jakarta.persistence.GenerationType;
import io.nova.exception.OptimisticLockingFailureException;
import io.nova.fetch.AnnotationFetchGroupBuilder;
import io.nova.fetch.FetchGroup;
import io.nova.metadata.CollectionTableDefinition;
import io.nova.metadata.ElementCollectionInfo;
import io.nova.metadata.EntityMetadata;
import io.nova.metadata.EntityMetadataFactory;
import io.nova.metadata.InheritanceInfo;
import io.nova.metadata.InheritanceLayout;
import io.nova.metadata.JoinTableDefinition;
import io.nova.metadata.ManyToManyInfo;
import io.nova.metadata.PersistentProperty;
import io.nova.metadata.SecondaryTableInfo;
import io.nova.query.AggregateRow;
import io.nova.query.AggregateSpec;
import io.nova.query.Aggregation;
import io.nova.query.Condition;
import io.nova.query.Criteria;
import io.nova.query.LockMode;
import io.nova.query.NativeQuery;
import io.nova.query.Page;
import io.nova.query.Pageable;
import io.nova.query.Projection;
import io.nova.query.QuerySpec;
import io.nova.query.Slice;
import io.nova.query.Updater;
import io.nova.sql.CompiledQuery;
import io.nova.sql.Dialect;
import io.nova.sql.SchemaGenerator;
import io.nova.sql.SqlRenderer;
import io.nova.sql.SqlStatement;
import io.nova.tx.ReactiveTransactionOperations;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.context.ContextView;

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
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * 엔티티 메타데이터, SQL 렌더러, executor를 기반으로 동작하는 기본 {@link ReactiveEntityOperations} 구현체다.
 */
public final class SimpleReactiveEntityOperations implements ReactiveEntityOperations {
    /**
     * 트랜잭션에 묶인 {@link PersistenceSession}을 Reactor {@code Context}에 보관하는 키. nova-r2dbc의
     * 커넥션 키와 동일한 메커니즘(deferContextual/contextWrite)으로 전파되며 그 sibling으로 공존한다.
     * nova-core가 소유하므로 어떤 트랜잭션 배선에서도 세션이 올바르게 얹힌다.
     */
    static final String SESSION_KEY = "io.nova.core.session";

    private final EntityMetadataFactory metadataFactory;
    private final Dialect dialect;
    private final SqlExecutor sqlExecutor;
    private final EntityStateDetector entityStateDetector;
    private final ReactiveTransactionOperations transactionOperations;
    private final Clock clock;
    private final AuditApplier auditApplier;
    private final EntityListenerInvoker listenerInvoker;
    private final AnnotationFetchGroupBuilder annotationFetchGroupBuilder;
    /**
     * 단순 엔티티(단일 @Id, 상속·soft-delete 없음)의 findById SELECT SQL 캐시. SQL 텍스트는 엔티티마다
     * 상수이므로 1회만 렌더해 재사용한다. 키는 factory가 캐시하는 immutable {@link EntityMetadata} 인스턴스다.
     */
    private final java.util.concurrent.ConcurrentHashMap<EntityMetadata<?>, String> selectByIdSqlCache =
            new java.util.concurrent.ConcurrentHashMap<>();
    /**
     * row 디코딩 시 entity를 만드는 no-arg 생성자 캐시(type별). row마다 reflective lookup을 반복하지 않는다.
     */
    private final java.util.concurrent.ConcurrentHashMap<Class<?>, Constructor<?>> constructorCache =
            new java.util.concurrent.ConcurrentHashMap<>();
    /**
     * {@code inTransaction} 안에서 영속성 세션(identity map + dirty checking)을 켤지 여부. 기본 {@code true}.
     * internal kill-switch로, 끄면 트랜잭션 동작이 세션 도입 이전과 byte-for-byte 동일하다(테스트/회귀 가드용).
     */
    private final boolean sessionEnabled;
    /**
     * {@code @GeneratedValue(TABLE)} generator별 in-memory 블록 할당 캐시. 키는 generator 테이블+행을
     * 식별하는 문자열, 값은 현재 블록 커서다. allocationSize만큼 한 번에 DB에서 확보한 뒤 블록을 소진할
     * 때까지 DB 왕복 없이 식별자를 발급한다(Hibernate pooled 방식). 트랜잭션 전파가 아니라 단순 할당 캐시이므로
     * ThreadLocal 금지 규약에 저촉되지 않는다.
     */
    private final java.util.concurrent.ConcurrentHashMap<String, TableGeneratorBlock> tableGeneratorBlocks =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * {@code @GeneratedValue(TABLE)} 블록 할당 시 compare-and-set 재시도 상한. 경합으로 CAS가 연속 실패하면
     * 이 횟수만큼만 재시도하고 fail-fast 한다(라이브락 방지). 정상 경합에서는 1~2회 안에 성공한다.
     */
    private static final int TABLE_GENERATOR_MAX_CAS_ATTEMPTS = 64;

    /**
     * to-one cascade(@ManyToOne/@OneToOne)에서 한 save 트리 동안 이미 처리 중인 인스턴스 집합을 공유하는 Reactor
     * Context 키. 양방향/self-reference cascade의 무한 재귀를 막는다.
     */
    private static final String CASCADE_VISITED_KEY = "io.nova.cascade.to-one.visited";

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
        this(metadataFactory, dialect, sqlExecutor, entityStateDetector, transactionOperations, clock, true);
    }

    SimpleReactiveEntityOperations(
            EntityMetadataFactory metadataFactory,
            Dialect dialect,
            SqlExecutor sqlExecutor,
            EntityStateDetector entityStateDetector,
            ReactiveTransactionOperations transactionOperations,
            Clock clock,
            boolean sessionEnabled
    ) {
        this.metadataFactory = metadataFactory;
        this.dialect = dialect;
        this.sqlExecutor = sqlExecutor;
        this.entityStateDetector = entityStateDetector;
        this.transactionOperations = transactionOperations;
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.auditApplier = new AuditApplier(this.clock);
        this.listenerInvoker = new EntityListenerInvoker();
        this.annotationFetchGroupBuilder = new AnnotationFetchGroupBuilder(metadataFactory);
        this.sessionEnabled = sessionEnabled;
    }

    @Override
    public <T> Mono<T> save(T entity) {
        return Mono.deferContextual(ctx -> {
            EntityMetadata<T> metadata = metadataFactory.getEntityMetadata(entityType(entity));
            Optional<PersistenceSession> session = currentSession(ctx);
            // @ManyToOne/@OneToOne(cascade=PERSIST/MERGE/ALL) 참조 엔티티를 owner INSERT/UPDATE 전에 먼저
            // 저장해 generated id를 확보한다 — owner row를 쓸 때 read()가 그 참조의 @Id를 FK 컬럼으로 추출하므로,
            // 참조가 먼저 저장돼 있어야 FK가 null이 아닌 값으로 바인딩된다(@OneToMany child-after-parent와 반대 순서).
            // 전파할 to-one cascade가 없으면 무비용으로 entity를 그대로 흘린다. @MapsId 파생 식별자도 이 시점
            // 이후에야 연관 엔티티의 PK가 확정되므로 cascade 선저장 뒤에 적용한다.
            Mono<T> ownerWithReferences = cascadeSaveToOneReferences(metadata, entity).thenReturn(entity);
            return ownerWithReferences.flatMap(owner -> {
                // @MapsId 파생 식별자: INSERT/UPDATE 결정 전에 연관 엔티티의 PK를 owner의 @Id로 복사한다.
                // 동기 작업이지만 누락된 연관 엔티티 등은 Mono.error로 흐르게 try/catch로 감싼다.
                try {
                    applyMapsIdDerivedIdentifier(metadata, owner);
                } catch (RuntimeException exception) {
                    return Mono.<T>error(exception);
                }
                Mono<T> saved = metadata.mapsIdProperty().isPresent()
                        // @MapsId는 app-assigned 파생키이므로 id-null isNew 휴리스틱과 충돌한다. 복합키와 동일하게
                        // 존재확인 SELECT로 insert/update를 가른다(세션 유무에 맞춰 stateless/session 동작 보존).
                        ? saveWithDerivedIdentifier(session, metadata, owner)
                        : session.isEmpty()
                        // 세션 밖(트랜잭션 미사용 등): 현행 stateless 동작 그대로.
                        ? saveStateless(metadata, owner)
                        : saveInSession(session.get(), metadata, owner);
                // entity row 저장 후 owning @ManyToMany link table과 @ElementCollection collection table을
                // full-replace로 동기화하고(둘 다 없으면 무비용), 마지막으로 @OneToMany(cascade=PERSIST/orphanRemoval)
                // child 전파를 reactive 순서대로 수행한다 — parent INSERT/UPDATE가 먼저 완료되어야 child FK 바인딩이 성립한다.
                return saved.flatMap(persisted ->
                        reconcileManyToManyLinks(metadata, persisted)
                                .then(reconcileElementCollections(metadata, persisted))
                                .then(cascadeSaveOneToManyChildren(metadata, persisted))
                                .then(reindexOrderedOneToManyChildren(metadata, persisted))
                                .thenReturn(persisted));
            });
        });
    }

    /**
     * {@code @ManyToOne(cascade=PERSIST/MERGE/ALL)} 또는 owning {@code @OneToOne(cascade=...)}의 참조 엔티티를
     * owner 저장 전에 먼저 {@link #save(Object)}로 재귀 저장한다. owner의 FK 컬럼 값은 {@link PersistentProperty#read(Object)}가
     * 이 참조의 @Id를 추출해 만들어지므로, 참조가 먼저 영속화돼 id를 가져야 FK가 올바르게 채워진다(현재 Reactor
     * Context=동일 세션/트랜잭션이 그대로 전파된다). 참조 필드가 null이거나 cascade-persist 관계가 없으면 무비용.
     * owning-side {@code @OneToOne}은 {@link EntityMetadata#manyToOneProperties()}에 포함되므로 한 자리에서 함께 처리된다.
     */
    private <T> Mono<Void> cascadeSaveToOneReferences(EntityMetadata<T> metadata, T owner) {
        List<PersistentProperty> cascading = metadata.manyToOneProperties().stream()
                .filter(PersistentProperty::cascadePersistReference)
                .toList();
        if (cascading.isEmpty()) {
            return Mono.empty();
        }
        return Mono.deferContextual(ctx -> {
            // cascade 경로에서 이미 처리 중인 인스턴스 집합(identity 기준)을 Reactor Context로 공유한다. 양방향
            // cascade(A→B, B→A)나 self-reference에서 save가 무한 재귀(StackOverflow)하지 않도록, 이미 방문한
            // 참조는 다시 저장하지 않는다. JPA가 persistence-context의 managed 추적으로 막는 것을 대체한다.
            java.util.Set<Object> visited = ctx.<java.util.Set<Object>>getOrEmpty(CASCADE_VISITED_KEY)
                    .orElseGet(() -> java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()));
            visited.add(owner);
            Mono<Void> chain = Flux.fromIterable(cascading)
                    .concatMap(property -> {
                        Object reference;
                        try {
                            reference = property.field().get(owner);
                        } catch (IllegalAccessException exception) {
                            return Mono.error(new IllegalStateException(
                                    "Cannot read to-one reference " + property.propertyName()
                                            + " for cascade on " + metadata.entityType().getName(), exception));
                        }
                        if (reference == null) {
                            // null 참조 = 이번 save에서 이 관계를 관리하지 않음 → cascade no-op.
                            return Mono.empty();
                        }
                        if (!visited.add(reference)) {
                            // 이미 cascade 경로에 있는 인스턴스(사이클/공유 참조) → 재저장하지 않는다(무한재귀 방지).
                            return Mono.empty();
                        }
                        // JPA 의미: PERSIST는 새(transient) 참조에만 전파된다. 이미 영속된(id 존재) 참조는 MERGE가
                        // 없으면 재저장하지 않는다 — 매 owner save마다 도달 가능한 to-one 그래프 전체를 다시 쓰는
                        // (존재확인 SELECT + UPDATE) 낭비를 막는다. 참조는 그대로 owner 필드에 남아 있어(이미 id 보유)
                        // owner row 쓰기에서 FK가 정상 바인딩된다.
                        Class<?> referenceType = property.manyToOneTargetType() != null
                                ? property.manyToOneTargetType() : reference.getClass();
                        Object referenceId = metadataFactory.getEntityMetadata(referenceType).readIdValue(reference);
                        if (referenceId != null && !property.cascadeMergeReference()) {
                            return Mono.empty();
                        }
                        // 참조 엔티티를 먼저 저장해 id를 확보한다. 반환된(=관리되는) 인스턴스를 owner 필드에 다시 set해
                        // 이후 owner row 쓰기에서 read()가 채워진 @Id를 FK로 추출하도록 한다.
                        return save(reference).doOnNext(savedReference -> {
                            try {
                                property.field().set(owner, savedReference);
                            } catch (IllegalAccessException exception) {
                                throw new IllegalStateException(
                                        "Cannot rebind cascaded to-one reference " + property.propertyName()
                                                + " on " + metadata.entityType().getName(), exception);
                            }
                        }).then();
                    })
                    .then();
            // visited 집합을 nested save(reference)가 보도록 Context로 전파한다.
            return chain.contextWrite(c -> c.put(CASCADE_VISITED_KEY, visited));
        });
    }

    /**
     * 세션이 없을 때의 save — 영속성 세션 도입 이전과 byte-for-byte 동일하다. 복합키는 존재 확인,
     * 단일키는 id-null isNew 휴리스틱으로 insert/update를 가른다.
     */
    private <T> Mono<T> saveStateless(EntityMetadata<T> metadata, T entity) {
        if (metadata.hasCompositeId()) {
            // @EmbeddedId 복합키는 application-assigned이라 id-null "isNew" 휴리스틱을 쓸 수 없다(키가 곧
            // 데이터라 save 시점에 항상 채워져 있음). JPA merge와 동일하게 존재 여부를 SELECT로 확인해
            // insert/update를 가른다. 단일 키 경로는 이 분기를 타지 않아 추가 round-trip이 없다.
            Object id = metadata.readIdValue(entity);
            if (id == null) {
                return Mono.error(new IllegalArgumentException(
                        "@EmbeddedId must not be null on save for " + metadata.entityType().getName()));
            }
            return findByIdInternal(metadata, id).hasElement()
                    .flatMap(exists -> exists ? updatePath(metadata, entity) : insertPath(metadata, entity));
        }
        boolean isNew = entityStateDetector.isNew(entity, metadata);
        return isNew ? insertPath(metadata, entity) : updatePath(metadata, entity);
    }

    /**
     * 세션 안에서의 save — 신규는 즉시 INSERT(생성 id 확보 + in-tx 가시성) 후 세션에 등록하고, 기존은 SQL
     * 없이 관리 대상으로 편입만 한다. 실제 UPDATE는 flush 시점의 dirty diff가 발행한다(JPA dirty checking).
     */
    private <T> Mono<T> saveInSession(PersistenceSession session, EntityMetadata<T> metadata, T entity) {
        if (metadata.hasCompositeId()) {
            Object id = metadata.readIdValue(entity);
            if (id == null) {
                return Mono.error(new IllegalArgumentException(
                        "@EmbeddedId must not be null on save for " + metadata.entityType().getName()));
            }
            return findByIdInternal(metadata, id).hasElement()
                    .flatMap(exists -> exists
                            ? registerExisting(session, metadata, entity)
                            : insertAndRegister(session, metadata, entity));
        }
        boolean isNew = entityStateDetector.isNew(entity, metadata);
        return isNew ? insertAndRegister(session, metadata, entity) : registerExisting(session, metadata, entity);
    }

    /**
     * {@code @MapsId} 파생 식별자(shared primary key) 엔티티의 save. 파생키는 application/연관-PK가 채우므로
     * 항상 채워져 있어(id-null isNew 휴리스틱 사용 불가) 복합키와 동일하게 존재확인 SELECT로 insert/update를
     * 가른다. 세션이 있으면 기존은 등록만(dirty flush가 UPDATE 발행), 신규는 즉시 INSERT 후 등록한다.
     */
    private <T> Mono<T> saveWithDerivedIdentifier(
            Optional<PersistenceSession> session, EntityMetadata<T> metadata, T entity) {
        Object id = metadata.idProperty().read(entity);
        if (id == null) {
            // applyMapsIdDerivedIdentifier가 채웠어야 한다. 여기 도달하면 연관 PK가 null이라는 뜻이다.
            return Mono.error(new IllegalStateException(
                    "@MapsId derived identifier was not resolved for " + metadata.entityType().getName()
                            + "; the associated entity must be persisted (non-null primary key) before saving"));
        }
        if (session.isEmpty()) {
            return findByIdInternal(metadata, id).hasElement()
                    .flatMap(exists -> exists ? updatePath(metadata, entity) : insertPath(metadata, entity));
        }
        PersistenceSession active = session.get();
        return findByIdInternal(metadata, id).hasElement()
                .flatMap(exists -> exists
                        ? registerExisting(active, metadata, entity)
                        : insertAndRegister(active, metadata, entity));
    }

    /**
     * {@code @MapsId} 관계가 있으면 연관 엔티티의 PK를 읽어 owner의 {@code @Id}에 복사한다. 관계 참조가
     * {@code null}이거나 연관 엔티티의 PK가 아직 {@code null}이면(미영속) fail-fast로 거부한다(조용한 무시
     * 금지). {@code @MapsId}가 없으면 무비용 no-op.
     */
    private <T> void applyMapsIdDerivedIdentifier(EntityMetadata<T> metadata, T entity) {
        PersistentProperty mapsIdProperty = metadata.mapsIdProperty().orElse(null);
        if (mapsIdProperty == null) {
            return;
        }
        Object associated;
        try {
            associated = mapsIdProperty.field().get(entity);
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException(
                    "Cannot read @MapsId relation " + mapsIdProperty.propertyName()
                            + " on " + metadata.entityType().getName(), exception);
        }
        if (associated == null) {
            throw new IllegalArgumentException(
                    metadata.entityType().getName() + "." + mapsIdProperty.propertyName()
                            + " @MapsId association must not be null on save;"
                            + " set the associated entity so its primary key can derive the identifier");
        }
        EntityMetadata<?> associatedMetadata =
                metadataFactory.getEntityMetadata(mapsIdProperty.manyToOneTargetType());
        Object associatedId = associatedMetadata.idProperty().read(associated);
        if (associatedId == null) {
            throw new IllegalArgumentException(
                    metadata.entityType().getName() + "." + mapsIdProperty.propertyName()
                            + " @MapsId association " + associatedMetadata.entityType().getName()
                            + " must be persisted (non-null primary key) before saving the owner");
        }
        metadata.idProperty().write(entity, metadata.idProperty().toPropertyValue(associatedId));
    }

    /**
     * 즉시 INSERT 후(audit/version/콜백 적용 완료, id 채워진 상태) 세션에 등록해 baseline 스냅샷을 찍는다.
     */
    private <T> Mono<T> insertAndRegister(PersistenceSession session, EntityMetadata<T> metadata, T entity) {
        return insertPath(metadata, entity)
                .doOnNext(saved -> session.registerOnPersist(metadata, saved));
    }

    /**
     * 기존 엔티티를 SQL 없이 세션에 편입한다. 이미 관리 중이면(예: findById로 로드 후 수정) 로드 스냅샷을
     * 보존해 flush가 변경분만 UPDATE하게 두고, 미관리(직접 만든 detached 엔티티)면 현재 상태를 baseline으로
     * 등록한다.
     */
    private <T> Mono<T> registerExisting(PersistenceSession session, EntityMetadata<T> metadata, T entity) {
        if (!session.isManaged(metadata, entity)) {
            session.registerOnPersist(metadata, entity);
        }
        return Mono.just(entity);
    }

    private Optional<PersistenceSession> currentSession(ContextView ctx) {
        return ctx.hasKey(SESSION_KEY) ? Optional.of(ctx.get(SESSION_KEY)) : Optional.empty();
    }

    /**
     * owning {@code @ManyToMany} 컬렉션을 link table에 full-replace로 동기화한다 — owner의 link row를 모두
     * 삭제하고 현재 컬렉션의 (owner, target) 쌍을 다시 insert한다. owning M2M가 없으면 무비용. {@link #sqlExecutor}만
     * 호출하므로 세션 auto-flush 재진입이 없다(flush 불변식 유지).
     */
    private <T> Mono<Void> reconcileManyToManyLinks(EntityMetadata<T> metadata, T owner) {
        List<PersistentProperty> owning = metadata.manyToManyProperties().stream()
                .filter(property -> property.manyToManyInfo().owning())
                .toList();
        if (owning.isEmpty()) {
            return Mono.empty();
        }
        Object ownerId = metadata.idProperty().read(owner);
        if (ownerId == null) {
            return Mono.error(new IllegalStateException(
                    "owner id must be set before reconciling @ManyToMany links on " + metadata.entityType().getName()));
        }
        return Flux.fromIterable(owning)
                .concatMap(property -> reconcileOneManyToMany(metadata, property, owner, ownerId))
                .then();
    }

    private Mono<Void> reconcileOneManyToMany(
            EntityMetadata<?> ownerMetadata, PersistentProperty property, Object owner, Object ownerId) {
        ManyToManyInfo info = property.manyToManyInfo();
        Object collection;
        try {
            collection = property.field().get(owner);
        } catch (IllegalAccessException exception) {
            return Mono.error(new IllegalStateException(
                    "Cannot read @ManyToMany collection " + property.propertyName(), exception));
        }
        if (collection == null) {
            // null 컬렉션 = "이번 save에서 이 관계를 관리하지 않음" → 삭제하지 않는다. (빈 컬렉션만 전체 삭제.)
            return Mono.empty();
        }
        EntityMetadata<?> targetMetadata = metadataFactory.getEntityMetadata(info.targetType());
        JoinTableDefinition definition = joinDefinition(ownerMetadata, info, targetMetadata);
        List<Object> targetIds = new ArrayList<>();
        for (Object element : (Iterable<?>) collection) {
            Object targetId = targetMetadata.idProperty().read(element);
            if (targetId == null) {
                return Mono.error(new IllegalStateException(
                        "@ManyToMany targets must be persisted (non-null id) before saving the owner on "
                                + ownerMetadata.entityType().getName() + "." + property.propertyName()));
            }
            targetIds.add(targetId);
        }
        SqlRenderer renderer = dialect.sqlRenderer();
        Mono<Void> delete = sqlExecutor.execute(renderer.deleteJoinRows(definition, ownerId)).then();
        if (targetIds.isEmpty()) {
            return delete;
        }
        return delete.thenMany(Flux.fromIterable(targetIds)
                        .concatMap(targetId -> sqlExecutor.execute(renderer.insertJoinRow(definition, ownerId, targetId))))
                .then();
    }

    private JoinTableDefinition joinDefinition(
            EntityMetadata<?> ownerMetadata, ManyToManyInfo info, EntityMetadata<?> targetMetadata) {
        return new JoinTableDefinition(
                info.joinTableName(),
                info.ownerForeignKeyColumn(),
                wrapPrimitive(ownerMetadata.idProperty().javaType()),
                info.targetForeignKeyColumn(),
                wrapPrimitive(targetMetadata.idProperty().javaType()));
    }

    /**
     * {@code @ElementCollection} 값 컬렉션을 collection table에 full-replace로 동기화한다 — owner의 값 row를
     * 모두 삭제하고 현재 컬렉션 원소들을 다시 insert한다. 값 컬렉션이 없으면 무비용. {@link #sqlExecutor}만 호출한다.
     */
    private <T> Mono<Void> reconcileElementCollections(EntityMetadata<T> metadata, T owner) {
        List<PersistentProperty> collections = metadata.elementCollectionProperties();
        if (collections.isEmpty()) {
            return Mono.empty();
        }
        Object ownerId = metadata.idProperty().read(owner);
        if (ownerId == null) {
            return Mono.error(new IllegalStateException(
                    "owner id must be set before reconciling @ElementCollection on " + metadata.entityType().getName()));
        }
        return Flux.fromIterable(collections)
                .concatMap(property -> reconcileOneElementCollection(metadata, property, owner, ownerId))
                .then();
    }

    private Mono<Void> reconcileOneElementCollection(
            EntityMetadata<?> ownerMetadata, PersistentProperty property, Object owner, Object ownerId) {
        ElementCollectionInfo info = property.elementCollectionInfo();
        Object collection;
        try {
            collection = property.field().get(owner);
        } catch (IllegalAccessException exception) {
            return Mono.error(new IllegalStateException(
                    "Cannot read @ElementCollection " + property.propertyName(), exception));
        }
        if (collection == null) {
            // null 컬렉션 = 이번 save에서 관리하지 않음 → 삭제하지 않는다(빈 컬렉션만 전체 삭제).
            return Mono.empty();
        }
        CollectionTableDefinition definition = collectionDefinition(ownerMetadata, info);
        SqlRenderer renderer = dialect.sqlRenderer();
        Mono<Void> delete = sqlExecutor.execute(renderer.deleteCollectionRows(definition, ownerId)).then();
        if (info.map()) {
            // Map<K,V>: full-replace로 (owner FK, key, value) 행을 다시 쓴다. null key/value entry는 skip한다.
            return reconcileMapEntries(info, definition, renderer, delete, ownerId, (Map<?, ?>) collection);
        }
        List<Object> elements = new ArrayList<>();
        for (Object value : (Iterable<?>) collection) {
            if (value != null) {
                elements.add(value);
            }
        }
        if (elements.isEmpty()) {
            return delete;
        }
        // @OrderColumn이면 0-기반 인덱스를 order 컬럼에 함께 기록한다. full-replace라 인덱스 = 현재 List 위치다.
        boolean ordered = definition.ordered();
        if (info.embeddable()) {
            // @Embeddable 원소: 각 원소의 펼친 필드 값들을 한 row의 다중 컬럼으로 insert한다.
            return delete.thenMany(Flux.range(0, elements.size())
                            .concatMap(index -> {
                                Object element = elements.get(index);
                                List<Object> columnValues = readEmbeddableColumnValues(info, element);
                                SqlStatement statement = ordered
                                        ? renderer.insertEmbeddableCollectionRow(definition, ownerId, columnValues, index)
                                        : renderer.insertEmbeddableCollectionRow(definition, ownerId, columnValues);
                                return sqlExecutor.execute(statement);
                            }))
                    .then();
        }
        return delete.thenMany(Flux.range(0, elements.size())
                        .concatMap(index -> {
                            Object value = elements.get(index);
                            SqlStatement statement = ordered
                                    ? renderer.insertCollectionRow(definition, ownerId, value, index)
                                    : renderer.insertCollectionRow(definition, ownerId, value);
                            return sqlExecutor.execute(statement);
                        }))
                .then();
    }

    /**
     * {@code @Embeddable} 원소 인스턴스에서 펼친 컬럼 순서대로 필드 값을 읽어 리스트로 만든다.
     * {@link ElementCollectionInfo#embeddableColumns()} 순서와 정렬된다.
     */
    private static List<Object> readEmbeddableColumnValues(ElementCollectionInfo info, Object element) {
        List<Object> values = new ArrayList<>(info.embeddableColumns().size());
        for (ElementCollectionInfo.EmbeddableColumn column : info.embeddableColumns()) {
            try {
                values.add(column.field().get(element));
            } catch (IllegalAccessException exception) {
                throw new IllegalStateException(
                        "Cannot read @Embeddable @ElementCollection field " + column.field().getName(), exception);
            }
        }
        return values;
    }

    /**
     * {@code @ElementCollection Map<K,V>}을 collection table에 full-replace로 다시 쓴다. 각 entry를 (owner FK,
     * key, value[s]) 행으로 insert하며, {@code null} key나 {@code null} value를 가진 entry는 건너뛴다(JPA map은
     * null key를 허용하지 않는다). value 표현은 기본 타입/{@code @Embeddable}을 그대로 재사용한다.
     */
    private Mono<Void> reconcileMapEntries(
            ElementCollectionInfo info, CollectionTableDefinition definition, SqlRenderer renderer,
            Mono<Void> delete, Object ownerId, Map<?, ?> map) {
        List<Map.Entry<?, ?>> entries = new ArrayList<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                entries.add(entry);
            }
        }
        if (entries.isEmpty()) {
            return delete;
        }
        boolean embeddable = info.embeddable();
        return delete.thenMany(Flux.fromIterable(entries)
                        .concatMap(entry -> {
                            Object key = encodeMapKey(info, entry.getKey());
                            SqlStatement statement;
                            if (embeddable) {
                                List<Object> columnValues = readEmbeddableColumnValues(info, entry.getValue());
                                statement = renderer.insertEmbeddableMapCollectionRow(definition, ownerId, key, columnValues);
                            } else {
                                statement = renderer.insertMapCollectionRow(definition, ownerId, key, entry.getValue());
                            }
                            return sqlExecutor.execute(statement);
                        }))
                .then();
    }

    /**
     * Map key를 저장 표현으로 인코딩한다 — enum key는 {@code @MapKeyEnumerated}에 따라 이름(STRING) 또는
     * ordinal(ORDINAL)로, 기본 타입 key는 그대로 둔다.
     */
    private static Object encodeMapKey(ElementCollectionInfo info, Object key) {
        ElementCollectionInfo.MapKeyInfo mapKey = info.mapKey();
        if (mapKey.enumKey()) {
            Enum<?> enumKey = (Enum<?>) key;
            return mapKey.keyEnumType() == EnumType.STRING ? enumKey.name() : enumKey.ordinal();
        }
        return key;
    }

    /**
     * collection table에서 읽은 저장 표현을 도메인 Map key로 디코딩한다 — enum key는 이름/ordinal에서 enum
     * 상수로, 기본 타입 key는 그대로 둔다.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object decodeMapKey(ElementCollectionInfo info, Object stored) {
        ElementCollectionInfo.MapKeyInfo mapKey = info.mapKey();
        if (!mapKey.enumKey()) {
            return stored;
        }
        Class<?> keyType = mapKey.keyType();
        if (mapKey.keyEnumType() == EnumType.STRING) {
            return Enum.valueOf((Class<? extends Enum>) keyType, (String) stored);
        }
        Object[] constants = keyType.getEnumConstants();
        int ordinal = ((Number) stored).intValue();
        if (ordinal < 0 || ordinal >= constants.length) {
            throw new IllegalStateException(
                    "Stored ordinal " + ordinal + " is out of range for enum map key " + keyType.getName());
        }
        return constants[ordinal];
    }

    private CollectionTableDefinition collectionDefinition(EntityMetadata<?> ownerMetadata, ElementCollectionInfo info) {
        // schema 생성과 동일한 정의를 공유한다(@Embeddable 펼침 / @OrderColumn / Map key 컬럼 일괄 반영).
        return info.toCollectionTableDefinition(wrapPrimitive(ownerMetadata.idProperty().javaType()));
    }

    /**
     * {@code @OneToMany(cascade=PERSIST/ALL/MERGE)} 또는 {@code orphanRemoval=true}가 지정된 컬렉션을 parent
     * save 직후 전파한다. cascade-persist는 각 child의 mappedBy {@code @ManyToOne} 역참조를 parent로 바인딩한 뒤
     * {@link #save(Object)}로 재귀 저장하고(현재 Reactor Context=동일 세션/트랜잭션이 그대로 전파된다),
     * orphanRemoval은 child를 모두 저장한 다음 "이 parent FK를 가지면서 현재 컬렉션에 없는" child를 삭제한다.
     * cascade도 orphanRemoval도 없는 marker-only {@code @OneToMany}는 무비용으로 건너뛴다.
     */
    private <T> Mono<Void> cascadeSaveOneToManyChildren(EntityMetadata<T> metadata, T parent) {
        List<PersistentProperty> cascading = metadata.oneToManyProperties().stream()
                .filter(property -> property.cascadePersistChildren() || property.orphanRemoval())
                .toList();
        if (cascading.isEmpty()) {
            return Mono.empty();
        }
        Object parentId = metadata.idProperty().read(parent);
        if (parentId == null) {
            return Mono.error(new IllegalStateException(
                    "parent id must be set before cascading @OneToMany children on "
                            + metadata.entityType().getName()));
        }
        return Flux.fromIterable(cascading)
                .concatMap(property -> cascadeOneToManyProperty(metadata, property, parent, parentId))
                .then();
    }

    private <T> Mono<Void> cascadeOneToManyProperty(
            EntityMetadata<T> metadata, PersistentProperty property, T parent, Object parentId) {
        if (property.oneToManyTargetType() == null) {
            return Mono.error(new IllegalStateException(
                    metadata.entityType().getName() + "." + property.propertyName()
                            + " @OneToMany(cascade/orphanRemoval) requires targetEntity to be specified"));
        }
        EntityMetadata<?> childMetadata = metadataFactory.getEntityMetadata(property.oneToManyTargetType());
        PersistentProperty mappedByProperty = resolveMappedByProperty(metadata, property, childMetadata);
        Object collection;
        try {
            collection = property.field().get(parent);
        } catch (IllegalAccessException exception) {
            return Mono.error(new IllegalStateException(
                    "Cannot read @OneToMany collection " + property.propertyName(), exception));
        }
        if (collection == null) {
            // null 컬렉션 = 이번 save에서 이 관계를 관리하지 않음 → cascade/orphanRemoval 모두 no-op.
            return Mono.empty();
        }
        List<Object> children = new ArrayList<>();
        for (Object child : (Iterable<?>) collection) {
            if (child != null) {
                children.add(child);
            }
        }
        Mono<Void> persistChildren = Mono.empty();
        if (property.cascadePersistChildren()) {
            persistChildren = Flux.fromIterable(children)
                    .concatMap(child -> {
                        // child의 역방향 @ManyToOne을 parent로 바인딩 → child save 시 FK 컬럼이 parent id로 채워진다.
                        bindParentReference(mappedByProperty, child, parent);
                        return save(child);
                    })
                    .then();
        }
        if (!property.orphanRemoval()) {
            return persistChildren;
        }
        // orphanRemoval: child를 모두 저장(=현재 컬렉션 child의 id 확정)한 뒤, 이 parent FK를 가지면서
        // 현재 컬렉션에 남지 않은 child row를 삭제한다. M2M/@ElementCollection의 full-replace reconcile과 동일 철학.
        // removeOrphans가 retainedIds를 동기적으로 읽으므로 반드시 subscription 시점(=persistChildren 완료 후)에
        // 호출되도록 Mono.defer로 감싼다. 그러지 않으면 assembly 시점에 아직 save 전인 child의 id(null)를 읽어
        // retainedIds가 비고, 결국 "이 parent의 child 전부 삭제"로 붕괴해 방금 저장한 child까지 지워진다.
        return persistChildren.then(
                Mono.defer(() -> removeOrphans(childMetadata, mappedByProperty, parentId, children)).then());
    }

    /**
     * orphanRemoval 삭제를 발행한다. child 측 mappedBy FK 컬럼이 parentId이면서 현재 컬렉션에 남은 child id가
     * 아닌 row를 모두 삭제한다. 현재 컬렉션이 비었으면 이 parent에 속한 child를 전부 삭제한다.
     */
    private Mono<Long> removeOrphans(
            EntityMetadata<?> childMetadata, PersistentProperty mappedByProperty, Object parentId, List<Object> children) {
        List<Object> retainedIds = new ArrayList<>();
        for (Object child : children) {
            Object childId = childMetadata.idProperty().read(child);
            if (childId != null) {
                retainedIds.add(childId);
            }
        }
        Condition fkMatches = Criteria.eq(mappedByProperty.propertyName(), parentId);
        QuerySpec spec = retainedIds.isEmpty()
                ? QuerySpec.empty().where(fkMatches)
                : QuerySpec.empty().where(Criteria.and(
                        fkMatches,
                        Criteria.notIn(childMetadata.idProperty().propertyName(), retainedIds)));
        return sqlExecutor.execute(dialect.sqlRenderer().deleteByQuery(childMetadata, spec));
    }

    /**
     * {@code @OneToMany(mappedBy)} + {@code @OrderColumn}으로 정렬되는 컬렉션의 순서를 parent save 직후 child
     * 테이블의 순서 컬럼에 0..n-1로 raw UPDATE한다 — full-replace 의미라 재정렬/삭제 후에도 현재 List 위치로
     * 재인덱싱된다. cascade 여부와 무관하게 실행되지만, child는 이미 영속(non-null id)이어야 한다(cascade=PERSIST가
     * 있으면 직전 단계가 보장하고, 없으면 사용자가 먼저 저장했어야 한다). 정렬 {@code @OneToMany}가 없으면 무비용이다.
     */
    private <T> Mono<Void> reindexOrderedOneToManyChildren(EntityMetadata<T> metadata, T parent) {
        List<PersistentProperty> ordered = metadata.oneToManyProperties().stream()
                .filter(property -> property.oneToManyOrderColumn() != null)
                .toList();
        if (ordered.isEmpty()) {
            return Mono.empty();
        }
        return Flux.fromIterable(ordered)
                .concatMap(property -> reindexOneToManyProperty(metadata, property, parent))
                .then();
    }

    private <T> Mono<Void> reindexOneToManyProperty(
            EntityMetadata<T> metadata, PersistentProperty property, T parent) {
        if (property.oneToManyTargetType() == null) {
            return Mono.error(new IllegalStateException(
                    metadata.entityType().getName() + "." + property.propertyName()
                            + " @OneToMany @OrderColumn requires targetEntity to be specified"));
        }
        EntityMetadata<?> childMetadata = metadataFactory.getEntityMetadata(property.oneToManyTargetType());
        String orderColumnName = property.oneToManyOrderColumn().columnName();
        Object collection;
        try {
            collection = property.field().get(parent);
        } catch (IllegalAccessException exception) {
            return Mono.error(new IllegalStateException(
                    "Cannot read @OneToMany collection " + property.propertyName(), exception));
        }
        if (collection == null) {
            // null 컬렉션 = 이번 save에서 이 관계를 관리하지 않음 → 순서 재인덱싱 no-op.
            return Mono.empty();
        }
        List<Object> children = new ArrayList<>();
        for (Object child : (Iterable<?>) collection) {
            if (child != null) {
                children.add(child);
            }
        }
        if (children.isEmpty()) {
            return Mono.empty();
        }
        SqlRenderer renderer = dialect.sqlRenderer();
        return Flux.range(0, children.size())
                .concatMap(index -> {
                    Object childId = childMetadata.idProperty().read(children.get(index));
                    if (childId == null) {
                        return Mono.error(new IllegalStateException(
                                metadata.entityType().getName() + "." + property.propertyName()
                                        + " @OneToMany @OrderColumn requires each child to be persisted"
                                        + " (non-null id) before reindexing; enable cascade=PERSIST or save"
                                        + " the children first"));
                    }
                    return sqlExecutor.execute(
                            renderer.updateOneToManyOrder(childMetadata, orderColumnName, childId, index));
                })
                .then();
    }

    /**
     * {@code @OneToMany(mappedBy)}가 가리키는 child 측 owning {@code @ManyToOne} property를 찾는다. 존재하지
     * 않거나 {@code @ManyToOne}이 아니면 fail-fast로 거부한다({@link AnnotationFetchGroupBuilder}의 FK 해석과 대칭).
     */
    private PersistentProperty resolveMappedByProperty(
            EntityMetadata<?> parentMetadata, PersistentProperty oneToMany, EntityMetadata<?> childMetadata) {
        String mappedBy = oneToMany.oneToManyMappedBy();
        PersistentProperty owning = childMetadata.findProperty(mappedBy)
                .orElseThrow(() -> new IllegalStateException(
                        parentMetadata.entityType().getName() + "." + oneToMany.propertyName()
                                + " @OneToMany(mappedBy=\"" + mappedBy + "\") does not exist on "
                                + childMetadata.entityType().getName()));
        if (!owning.manyToOne()) {
            throw new IllegalStateException(
                    parentMetadata.entityType().getName() + "." + oneToMany.propertyName()
                            + " @OneToMany(mappedBy=\"" + mappedBy + "\") refers to a non-@ManyToOne property on "
                            + childMetadata.entityType().getName());
        }
        // cascade/orphanRemoval 경로는 child의 단일 @Id(idProperty + 단일 컬럼 Criteria)에 의존한다. 복합키
        // (@EmbeddedId/@IdClass) child는 orphan 삭제·FK 매칭 의미가 정의되지 않아 silent 오작동 위험이 있으므로
        // fail-fast로 거부한다(다른 복합키 관계 제약과 대칭).
        if (childMetadata.hasCompositeId()) {
            throw new IllegalStateException(
                    parentMetadata.entityType().getName() + "." + oneToMany.propertyName()
                            + " @OneToMany(cascade/orphanRemoval) is not supported when the child "
                            + childMetadata.entityType().getName()
                            + " has a composite id; persist/remove such children explicitly");
        }
        return owning;
    }

    /**
     * child의 owning {@code @ManyToOne} 필드에 parent 인스턴스를 직접 set한다. child save 시 그 property의
     * {@link PersistentProperty#read(Object)}가 parent의 @Id를 추출해 FK 컬럼에 바인딩한다.
     */
    private static void bindParentReference(PersistentProperty mappedByProperty, Object child, Object parent) {
        try {
            mappedByProperty.field().set(child, parent);
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException(
                    "Cannot bind @ManyToOne back-reference " + mappedByProperty.propertyName()
                            + " on cascaded child", exception);
        }
    }

    private <T> Mono<T> insertPath(EntityMetadata<T> metadata, T entity) {
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
        return insertNew(metadata, entity)
                .doOnNext(saved -> listenerInvoker.invokePostPersist(saved, metadata));
    }

    private <T> Mono<T> updatePath(EntityMetadata<T> metadata, T entity) {
        try {
            auditApplier.applyOnUpdate(entity, metadata);
            listenerInvoker.invokePreUpdate(entity, metadata);
        } catch (RuntimeException exception) {
            return Mono.error(exception);
        }
        return updateExisting(metadata, entity)
                .doOnNext(saved -> listenerInvoker.invokePostUpdate(saved, metadata));
    }

    private <T> Mono<T> insertNew(EntityMetadata<T> metadata, T entity) {
        if (metadata.hasInheritance() && metadata.inheritance().joined()) {
            // JOINED: 루트 INSERT(생성 id 확보) → 서브타입 INSERT(같은 id를 FK로). reactive 순서로 보장한다.
            return insertJoined(metadata, entity);
        }
        Mono<T> primary = insertPrimaryRow(metadata, entity);
        if (!metadata.hasSecondaryTables()) {
            return primary;
        }
        // @SecondaryTable: primary INSERT로 PK를 확보한 뒤 같은 PK 값으로 각 보조 테이블 행을 INSERT한다.
        // 동일 reactive 체인(=동일 tx/세션 커넥션)에서 primary 다음에 순차 실행된다.
        return primary.flatMap(saved -> insertSecondaryRows(metadata, saved).thenReturn(saved));
    }

    /**
     * primary 테이블 INSERT만 수행한다(생성 키 전략별 분기 포함). 보조 테이블 INSERT는 호출자({@link #insertNew})가
     * primary INSERT 완료 후 이어서 발행한다.
     */
    private <T> Mono<T> insertPrimaryRow(EntityMetadata<T> metadata, T entity) {
        if (metadata.hasCompositeId()) {
            // @EmbeddedId 복합키는 generation 전략이 없는 application-assigned이므로 그대로 INSERT한다.
            SqlStatement statement = dialect.sqlRenderer().insert(metadata, entity);
            return sqlExecutor.execute(statement).thenReturn(entity);
        }
        PersistentProperty idProperty = metadata.idProperty();
        GenerationType strategy = idProperty.generationType();
        if (strategy == GenerationType.TABLE) {
            return nextTableGeneratorId(idProperty)
                    .flatMap(value -> {
                        idProperty.write(entity, idProperty.toPropertyValue(coerceIdType(idProperty, value)));
                        SqlStatement statement = dialect.sqlRenderer().insert(metadata, entity);
                        return sqlExecutor.execute(statement).thenReturn(entity);
                    });
        }
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

    /**
     * JOINED 상속 INSERT — 루트 테이블 INSERT를 먼저 발행해 id를 확정한 뒤(IDENTITY는 생성 키 회수,
     * SEQUENCE/TABLE/UUID는 사전 할당), 서브타입 테이블 INSERT를 같은 id를 FK로 발행한다. 두 INSERT는
     * {@link Mono#flatMap}으로 순차 보장되며, 동일 트랜잭션/세션 커넥션에서 실행된다(Reactor Context 전파).
     */
    private <T> Mono<T> insertJoined(EntityMetadata<T> metadata, T entity) {
        io.nova.metadata.InheritanceLayout layout =
                metadataFactory.inheritanceLayout(metadata.inheritance().root());
        InheritanceLayout.ConcreteSubtype subtype = resolveConcreteSubtype(layout, metadata.entityType());
        SqlRenderer renderer = dialect.sqlRenderer();
        PersistentProperty idProperty = metadata.idProperty();
        GenerationType strategy = idProperty.generationType();
        String rootTable = metadata.inheritance().rootTableName();

        Mono<T> rootInserted;
        if (strategy == GenerationType.IDENTITY) {
            SqlStatement rootInsert = renderer.insertJoinedRoot(
                    metadata, rootTable, layout.rootTableColumns(), entity);
            rootInserted = sqlExecutor.executeAndReturnGeneratedKey(
                            rootInsert, idProperty.columnName(), wrapPrimitive(idProperty.javaType()))
                    .map(key -> {
                        idProperty.write(entity, idProperty.toPropertyValue(key));
                        return entity;
                    })
                    .defaultIfEmpty(entity);
        } else if (strategy == GenerationType.SEQUENCE) {
            Class<?> idColumnType = wrapPrimitive(idProperty.javaType());
            rootInserted = sqlExecutor.queryOne(
                            new SqlStatement(dialect.sequenceNextValueSql(idProperty.generator()), List.of()),
                            row -> row.get(Dialect.SEQUENCE_VALUE_COLUMN, idColumnType))
                    .flatMap(value -> {
                        idProperty.write(entity, idProperty.toPropertyValue(value));
                        SqlStatement rootInsert = renderer.insertJoinedRoot(
                                metadata, rootTable, layout.rootTableColumns(), entity);
                        return sqlExecutor.execute(rootInsert).thenReturn(entity);
                    });
        } else if (strategy == GenerationType.TABLE) {
            rootInserted = nextTableGeneratorId(idProperty)
                    .flatMap(value -> {
                        idProperty.write(entity, idProperty.toPropertyValue(coerceIdType(idProperty, value)));
                        SqlStatement rootInsert = renderer.insertJoinedRoot(
                                metadata, rootTable, layout.rootTableColumns(), entity);
                        return sqlExecutor.execute(rootInsert).thenReturn(entity);
                    });
        } else {
            if (strategy == GenerationType.UUID) {
                assignUuidId(idProperty, entity);
            }
            SqlStatement rootInsert = renderer.insertJoinedRoot(
                    metadata, rootTable, layout.rootTableColumns(), entity);
            rootInserted = sqlExecutor.execute(rootInsert).thenReturn(entity);
        }
        return rootInserted.flatMap(saved -> {
            SqlStatement subInsert = renderer.insertJoinedSubtype(metadata, subtype.ownTableColumns(), saved);
            return sqlExecutor.execute(subInsert).thenReturn(saved);
        });
    }

    /**
     * layout에서 주어진 구체 타입의 {@link InheritanceLayout.ConcreteSubtype}을 찾는다.
     */
    private static InheritanceLayout.ConcreteSubtype resolveConcreteSubtype(
            io.nova.metadata.InheritanceLayout layout, Class<?> concreteType) {
        for (InheritanceLayout.ConcreteSubtype subtype : layout.subtypes()) {
            if (subtype.metadata().entityType() == concreteType) {
                return subtype;
            }
        }
        throw new IllegalStateException(
                concreteType.getName() + " is not a registered concrete subtype of "
                        + layout.info().root().getName());
    }

    /**
     * {@code @GeneratedValue(TABLE)} 식별자의 다음 값을 발급한다. 먼저 in-memory 블록에서 발급을 시도하고,
     * 블록이 비었으면 {@link #allocateTableGeneratorBlock}으로 generator 카운터에서 read-then-compare-and-set
     * 으로 새 블록을 원자 확보한다. 트랜잭션 없는(autocommit·커넥션 분리) 기본 save 경로에서도 두 saver가 같은
     * 블록을 발급받지 못한다. 발급된 값은 항상 {@code long}이며 호출자가 식별자 타입으로 coerce 한다.
     */
    private Mono<Long> nextTableGeneratorId(PersistentProperty idProperty) {
        io.nova.metadata.TableGeneratorInfo info = idProperty.tableGeneratorInfo();
        String key = info.table() + ' ' + info.pkColumnName() + ' ' + info.pkColumnValue();
        TableGeneratorBlock block = tableGeneratorBlocks.computeIfAbsent(key, k -> new TableGeneratorBlock());
        Long ready = block.next();
        if (ready != null) {
            return Mono.just(ready);
        }
        return allocateTableGeneratorBlock(info, block, TABLE_GENERATOR_MAX_CAS_ATTEMPTS);
    }

    /**
     * generator 카운터에서 allocationSize만큼의 식별자 블록을 read-then-compare-and-set으로 확보하고 첫
     * 식별자를 발급한다. 현재 값을 select한 뒤 그 값을 기대치로 CAS UPDATE를 시도하고, 0행이면(다른 saver가
     * 먼저 카운터를 옮김) 다시 읽어 재시도한다. CAS 성공 블록은 항상 서로소이므로 in-memory 블록 overwrite
     * 경합이 있어도 중복 id는 생기지 않는다(최악의 경우 id gap만 발생 — JPA가 허용). 매 시도는 한 번 더
     * {@link TableGeneratorBlock#next()}를 확인해 다른 subscriber가 방금 refill 한 경우 DB 왕복을 피한다.
     */
    private Mono<Long> allocateTableGeneratorBlock(
            io.nova.metadata.TableGeneratorInfo info, TableGeneratorBlock block, int attemptsLeft) {
        // next() 부수효과는 구독 시점에만 발생해야 한다(여러 번 구독/재시도 시 assembly-time 발급 방지). 따라서
        // 진입부 전체를 Mono.defer로 감싼다.
        return Mono.defer(() -> {
            Long ready = block.next();
            if (ready != null) {
                return Mono.just(ready);
            }
            if (attemptsLeft <= 0) {
                return Mono.error(new IllegalStateException(
                        "@GeneratedValue(TABLE) generator '" + info.pkColumnValue() + "' on " + info.table()
                                + " could not allocate an id block after repeated compare-and-set contention"));
            }
            // 블록 소진: per-block single-flight로 refill한다. 동시에 소진한 subscriber들은 같은 DB refill을
            // 공유하고 완료 후 각자 next()로 발급받아 id gap 낭비를 없앤다(서로소 블록은 CAS가 보장 → 중복 없음).
            return block.refillOnce(() -> refillTableGeneratorBlock(info, block, TABLE_GENERATOR_MAX_CAS_ATTEMPTS))
                    .then(allocateTableGeneratorBlock(info, block, attemptsLeft - 1));
        });
    }

    private Mono<Void> refillTableGeneratorBlock(
            io.nova.metadata.TableGeneratorInfo info, TableGeneratorBlock block, int attemptsLeft) {
        return Mono.defer(() -> {
            if (attemptsLeft <= 0) {
                return Mono.error(new IllegalStateException(
                        "@GeneratedValue(TABLE) generator '" + info.pkColumnValue() + "' on " + info.table()
                                + " could not allocate an id block after repeated compare-and-set contention"));
            }
            int allocationSize = info.allocationSize();
            SqlStatement select = new SqlStatement(
                    dialect.tableGeneratorSelectSql(
                            info.table(), info.valueColumnName(), info.pkColumnName(), info.pkColumnValue()),
                    List.of());
            return sqlExecutor.queryOne(select, row -> row.get(Dialect.TABLE_GENERATOR_VALUE_COLUMN, Long.class))
                    .flatMap(current -> {
                        long next = current + allocationSize;
                        SqlStatement cas = new SqlStatement(
                                dialect.tableGeneratorCompareAndSetSql(
                                        info.table(), info.valueColumnName(), info.pkColumnName(),
                                        info.pkColumnValue(), current, next),
                                List.of());
                        return sqlExecutor.execute(cas).flatMap(affected -> {
                            if (affected != null && affected == 1L) {
                                block.refill(next, allocationSize);
                                // 블록을 채운 즉시 single-flight 참조를 비운다(다음 소진은 새 refill). 이미 이
                                // refill을 공유 중인 동시 대기자들은 그대로 완료를 받고 각자 next()로 발급받는다.
                                block.clearInFlight();
                                return Mono.<Void>empty();
                            }
                            // CAS 패배: 새 현재값을 다시 읽어 서로소 블록을 재확보한다.
                            return refillTableGeneratorBlock(info, block, attemptsLeft - 1);
                        });
                    });
        });
    }

    /**
     * generator가 발급한 {@code long} 값을 식별자 property의 선언 타입(Long/Integer)으로 변환한다.
     */
    private static Object coerceIdType(PersistentProperty idProperty, long value) {
        Class<?> type = wrapPrimitive(idProperty.javaType());
        if (type == Integer.class) {
            return Math.toIntExact(value);
        }
        return value;
    }

    /**
     * {@code @GeneratedValue(TABLE)} generator의 in-memory 블록 커서. 한 번의 DB 왕복으로 확보한 식별자
     * 블록을 소진할 때까지 lock 없는 {@code synchronized} 임계구역에서 순차 발급한다. 발급량이 작고 임계구역이
     * 짧아 경합 비용은 무시 가능하며, reactive 흐름과 무관하게 정확한 단조 증가 식별자를 보장한다.
     */
    private static final class TableGeneratorBlock {
        private long nextId;
        private long blockMax;
        private boolean exhausted = true;
        // 진행 중인 refill을 공유하는 single-flight 홀더(동시 소진 시 DB refill 1회만 수행).
        private final java.util.concurrent.atomic.AtomicReference<Mono<Void>> inFlightRefill =
                new java.util.concurrent.atomic.AtomicReference<>();

        synchronized Long next() {
            if (exhausted || nextId > blockMax) {
                return null;
            }
            return nextId++;
        }

        /**
         * 카운터를 allocationSize만큼 증가시킨 결과({@code newValue})로 블록 [newValue - allocationSize,
         * newValue - 1]을 채운다(발급은 호출자가 {@link #next()}로). seed가 initialValue이므로 첫 블록의 첫
         * id는 정확히 initialValue다.
         */
        synchronized void refill(long newValue, int allocationSize) {
            this.nextId = newValue - allocationSize;
            this.blockMax = newValue - 1;
            this.exhausted = false;
        }

        /** 진행 중 refill 공유 참조를 비운다 — 다음 소진이 새 refill을 시작하게 한다. */
        void clearInFlight() {
            inFlightRefill.set(null);
        }

        /**
         * refill을 per-block single-flight로 실행한다. 진행 중인 refill이 있으면 그것을 공유하고, 없으면
         * {@code refillFactory}로 하나를 만들어 캐시(공유)한다. 공유 참조는 refill 본체가 성공(블록 채운 직후)
         * 또는 실패(error) 시 비운다 — doFinally에 의존하지 않아 결정적이다. 한 번의 DB refill이 동시 대기자
         * 모두를 채워 id gap 낭비를 막는다(서로소 블록은 CAS가 보장 → 중복 id 없음).
         */
        Mono<Void> refillOnce(java.util.function.Supplier<Mono<Void>> refillFactory) {
            return Mono.defer(() -> inFlightRefill.updateAndGet(existing -> {
                if (existing != null) {
                    return existing;
                }
                return refillFactory.get()
                        .onErrorResume(error -> {
                            clearInFlight();
                            return Mono.error(error);
                        })
                        .cache();
            }));
        }
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
        if (metadata.hasInheritance() && metadata.inheritance().joined()) {
            // JOINED: 루트 테이블(공통 컬럼)과 서브타입 테이블(자기 컬럼)을 각각 UPDATE한다. @Version 미지원.
            return updateJoined(metadata, entity);
        }
        Mono<T> primary = updatePrimaryRow(metadata, entity);
        if (!metadata.hasSecondaryTables()) {
            return primary;
        }
        // @SecondaryTable: primary UPDATE 후 각 보조 테이블 행을 UPDATE한다(updatable 컬럼 없는 테이블은 건너뜀).
        return primary.flatMap(saved -> updateSecondaryRows(metadata, saved).thenReturn(saved));
    }

    /**
     * primary 테이블 UPDATE(@Version 낙관락 포함)만 수행한다. 보조 테이블 UPDATE는 호출자({@link #updateExisting})가
     * 이어서 발행한다.
     */
    private <T> Mono<T> updatePrimaryRow(EntityMetadata<T> metadata, T entity) {
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
                                        + " id=" + metadata.readIdValue(entity)
                                        + " version=" + current));
                    }
                    versionProperty.write(entity, next);
                    return Mono.just(entity);
                });
    }

    /**
     * entity INSERT 직후 각 보조 테이블({@code @SecondaryTable})에 같은 primary PK 값으로 행을 INSERT한다.
     * 보조 테이블이 모든 컬럼이 null이어도 행을 항상 만들어, 이후 UPDATE/LEFT JOIN SELECT가 성립하게 한다.
     * {@link #sqlExecutor}만 호출하므로 세션 auto-flush 재진입이 없다.
     */
    private Mono<Void> insertSecondaryRows(EntityMetadata<?> metadata, Object entity) {
        SqlRenderer renderer = dialect.sqlRenderer();
        return Flux.fromIterable(metadata.secondaryTables())
                .concatMap(secondary -> sqlExecutor.execute(renderer.insertSecondary(metadata, secondary, entity)))
                .then();
    }

    /**
     * entity UPDATE 직후 <b>모든</b> 보조 테이블 행을 full UPDATE한다(full save 경로 전용 — 어떤 컬럼이
     * 변경됐는지 알 수 없으므로 전 보조 테이블을 entity의 현재 값으로 덮어쓴다). 보조 테이블에 updatable
     * 컬럼이 없으면(렌더러가 {@code null} 반환) 그 단계는 건너뛴다. {@link #sqlExecutor}만 호출한다.
     * <p>
     * 계약(마이그레이션 엣지): 보조 행이 부재한 데이터(예: 보조 테이블 도입 전에 적재된 레거시 primary 행)를
     * UPDATE하면 해당 보조 테이블 UPDATE는 0행에 영향하고 <em>조용히</em> 끝난다 — upsert(없으면 INSERT)가
     * 아니다. 보조 행은 항상 {@link #insertSecondaryRows}(=INSERT 경로)에서만 만들어진다.
     */
    private Mono<Void> updateSecondaryRows(EntityMetadata<?> metadata, Object entity) {
        SqlRenderer renderer = dialect.sqlRenderer();
        return Flux.fromIterable(metadata.secondaryTables())
                .concatMap(secondary -> {
                    SqlStatement statement = renderer.updateSecondary(metadata, secondary, entity);
                    return statement == null ? Mono.<Long>empty() : sqlExecutor.execute(statement);
                })
                .then();
    }

    /**
     * partial/dirty UPDATE 경로 전용 — {@code changedProperties}에 자기 컬럼이 하나라도 포함된 보조 테이블만
     * full UPDATE한다. primary 컬럼만 바뀐 변경은 어떤 보조 테이블도 건드리지 않아(불필요한 write 제거) 정확성을
     * 유지하면서 쓰기 폭을 dirty 보조 테이블로 좁힌다. {@link #updateSecondaryRows(EntityMetadata, Object)}의
     * 마이그레이션-엣지(보조 행 부재 시 0행 silent, upsert 아님) 계약은 동일하게 적용된다.
     */
    private Mono<Void> updateSecondaryRows(
            EntityMetadata<?> metadata, Object entity, java.util.Set<String> changedProperties) {
        SqlRenderer renderer = dialect.sqlRenderer();
        return Flux.fromIterable(metadata.secondaryTables())
                .filter(secondary -> secondaryTableTouchedBy(metadata, secondary, changedProperties))
                .concatMap(secondary -> {
                    SqlStatement statement = renderer.updateSecondary(metadata, secondary, entity);
                    return statement == null ? Mono.<Long>empty() : sqlExecutor.execute(statement);
                })
                .then();
    }

    /**
     * 주어진 보조 테이블의 컬럼 매핑 property 중 하나라도 {@code changedProperties}에 들어 있는지 — 즉 이번
     * 변경이 이 보조 테이블을 실제로 더럽혔는지 판정한다.
     */
    private static boolean secondaryTableTouchedBy(
            EntityMetadata<?> metadata, SecondaryTableInfo secondary, java.util.Set<String> changedProperties) {
        if (changedProperties.isEmpty()) {
            return false;
        }
        for (PersistentProperty property : metadata.secondaryColumnMappedProperties(secondary)) {
            if (changedProperties.contains(property.propertyName())) {
                return true;
            }
        }
        return false;
    }

    /**
     * primary DELETE 전에 각 보조 테이블 행을 먼저 삭제한다(보조 테이블이 primary PK를 FK로 참조하므로 FK 의존성
     * 보존). {@link #sqlExecutor}만 호출한다.
     */
    private Mono<Void> deleteSecondaryRows(EntityMetadata<?> metadata, Object id) {
        SqlRenderer renderer = dialect.sqlRenderer();
        return Flux.fromIterable(metadata.secondaryTables())
                .concatMap(secondary -> sqlExecutor.execute(renderer.deleteSecondaryById(metadata, secondary, id)))
                .then();
    }

    /**
     * JOINED 상속 UPDATE — 루트 테이블(공통 컬럼) UPDATE 후 서브타입 테이블(자기 컬럼) UPDATE를 순차 발행한다.
     * 서브타입에 자기 컬럼이 없으면 그 단계는 건너뛴다.
     */
    private <T> Mono<T> updateJoined(EntityMetadata<T> metadata, T entity) {
        InheritanceLayout layout = metadataFactory.inheritanceLayout(metadata.inheritance().root());
        InheritanceLayout.ConcreteSubtype subtype = resolveConcreteSubtype(layout, metadata.entityType());
        SqlRenderer renderer = dialect.sqlRenderer();
        String rootTable = metadata.inheritance().rootTableName();
        SqlStatement rootUpdate = renderer.updateJoinedRoot(
                metadata, rootTable, layout.rootTableColumns(), entity);
        Mono<Long> updateRoot = sqlExecutor.execute(rootUpdate);
        SqlStatement subUpdate = renderer.updateJoinedSubtype(metadata, subtype.ownTableColumns(), entity);
        if (subUpdate == null) {
            return updateRoot.thenReturn(entity);
        }
        return updateRoot.then(sqlExecutor.execute(subUpdate)).thenReturn(entity);
    }

    @Override
    public <T> Mono<T> update(T entity, Iterable<String> fields) {
        Objects.requireNonNull(entity, "entity must not be null");
        Objects.requireNonNull(fields, "fields must not be null");
        EntityMetadata<T> metadata = metadataFactory.getEntityMetadata(entityType(entity));
        Object id = metadata.readIdValue(entity);
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
        // @SecondaryTable: 요청 필드 중 보조 컬럼만 있고 primary 컬럼이 전혀 없으면(@Version/@UpdatedAt 미선언)
        // primary partial UPDATE를 건너뛴다(빈 SET SQL 방지). primary 컬럼이 하나라도 있으면 기존대로 primary
        // UPDATE를 발행한다. 빈 필드 목록은 renderer가 "update requires at least one field"로 거부하도록 그대로
        // 흘려보낸다(보조 컬럼이 실제로 요청됐을 때만 skip). 보조 테이블은 항상 full UPDATE로 동기화한다(idempotent).
        boolean hasPrimaryField = false;
        boolean hasSecondaryField = false;
        for (String fieldName : effectiveFields) {
            PersistentProperty property = metadata.findProperty(fieldName).orElse(null);
            if (property == null) {
                continue;
            }
            if (property.secondary()) {
                hasSecondaryField = true;
            } else {
                hasPrimaryField = true;
            }
        }
        // @Version 계약: versionProperty가 있으면 위 augment 단계가 version 컬럼(=primary)을 effectiveFields에
        // 항상 추가하므로 hasPrimaryField가 참이 되어 primary UPDATE가 반드시 발행된다 — 즉 보조 컬럼만 요청한
        // secondary-only update도 version을 bump+검증해 lost-update를 탐지한다(JPA 의미). @Version이 없을 때만
        // skipPrimaryUpdate가 성립해, 보조 컬럼만 바뀐 변경이 primary 빈 SET 없이 보조 테이블만 갱신한다(이
        // 경우 검증할 version이 없으므로 lost-update 미탐지는 설계상 정상).
        boolean skipPrimaryUpdate = metadata.hasSecondaryTables() && !hasPrimaryField && hasSecondaryField;
        Mono<T> primaryUpdate;
        if (skipPrimaryUpdate) {
            primaryUpdate = Mono.just(entity);
        } else if (versionProperty == null) {
            SqlStatement statement = dialect.sqlRenderer().update(metadata, entity, effectiveFields);
            primaryUpdate = sqlExecutor.execute(statement).thenReturn(entity);
        } else {
            SqlStatement statement = dialect.sqlRenderer().update(metadata, entity, effectiveFields);
            Object current = versionProperty.read(entity);
            Object next = nextVersionValue(versionProperty, current);
            primaryUpdate = sqlExecutor.execute(statement)
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
        // 요청 필드(+augment) 중 보조 컬럼이 포함된 보조 테이블만 갱신한다 — primary 컬럼만 요청한 partial
        // update가 매번 전 보조 테이블을 덮어쓰던 불필요한 write를 제거한다(정확성 유지).
        LinkedHashSet<String> requestedFields = new LinkedHashSet<>();
        for (String field : effectiveFields) {
            requestedFields.add(field);
        }
        Mono<T> result = metadata.hasSecondaryTables()
                ? primaryUpdate.flatMap(saved ->
                        updateSecondaryRows(metadata, saved, requestedFields).thenReturn(saved))
                : primaryUpdate;
        return result.doOnNext(updated -> listenerInvoker.invokePostUpdate(updated, metadata));
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
        return Mono.deferContextual(ctx -> {
            EntityMetadata<T> metadata = metadataFactory.getEntityMetadata(entityType);
            Optional<PersistenceSession> session = currentSession(ctx);
            // 세션이 없으면(트랜잭션 밖 등) auto-flush/manage 연산자 없이 곧장 조회한다(핫패스 오버헤드 제거).
            // 세션이 있으면 SELECT 전 auto-flush(읽기 일관성)하고, 결과를 identity map에 편입(같은 PK=같은 인스턴스).
            Mono<T> base = session.isEmpty()
                    ? findByIdInternal(metadata, id)
                    : autoFlushIfSession(session)
                            .then(findByIdInternal(metadata, id).map(entity -> manage(session, entity)));
            if (!metadata.hasRelationProperties()) {
                return base;
            }
            FetchGroup<T> annotationGroup = annotationFetchGroupBuilder.buildFor(entityType);
            return base.flatMap(parent ->
                    hydrateChildren(List.of(parent), annotationGroup)
                            .then(hydrateManyToMany(List.of(parent), metadata))
                            .then(hydrateElementCollections(List.of(parent), metadata))
                            .thenReturn(parent));
        });
    }

    @Override
    public <T> Flux<T> findAll(Class<T> entityType, QuerySpec querySpec) {
        return Flux.deferContextual(ctx -> {
            EntityMetadata<T> metadata = metadataFactory.getEntityMetadata(entityType);
            Optional<PersistenceSession> session = currentSession(ctx);
            Flux<T> base = session.isEmpty()
                    ? findAllInternal(metadata, querySpec)
                    : autoFlushIfSession(session)
                            .thenMany(findAllInternal(metadata, querySpec).map(entity -> manage(session, entity)));
            if (!metadata.hasRelationProperties()) {
                return base;
            }
            FetchGroup<T> annotationGroup = annotationFetchGroupBuilder.buildFor(entityType);
            return base.collectList()
                    .flatMapMany(parents ->
                            hydrateChildren(parents, annotationGroup)
                                    .then(hydrateManyToMany(parents, metadata))
                                    .then(hydrateElementCollections(parents, metadata))
                                    .thenMany(Flux.fromIterable(parents)));
        });
    }

    /**
     * 세션이 있으면 flush를, 없으면 no-op({@link Mono#empty()})을 반환한다. SELECT 전 auto-flush 진입점.
     */
    private Mono<Void> autoFlushIfSession(Optional<PersistenceSession> session) {
        return session.map(this::flush).orElseGet(Mono::empty);
    }

    /**
     * 갓 로드한 엔티티를 세션 identity map에 편입하고 canonical 인스턴스를 반환한다. 세션이 없으면 그대로
     * 반환한다. inheritance에서 row가 서브타입 인스턴스로 디코딩될 수 있으므로 concrete 클래스의 메타데이터로
     * 등록해 스냅샷/diff가 올바른 컬럼 집합을 쓰게 한다.
     */
    @SuppressWarnings("unchecked")
    private <T> T manage(Optional<PersistenceSession> session, T entity) {
        if (session.isEmpty() || entity == null) {
            return entity;
        }
        EntityMetadata<T> concrete = (EntityMetadata<T>) metadataFactory.getEntityMetadata(entity.getClass());
        return session.get().registerOnLoad(concrete, entity);
    }

    /**
     * 세션의 managed 엔티티들을 dirty diff해 변경분만 부분 UPDATE로 발행한다. 변경이 없으면 SQL을 내지
     * 않는다. 엔트리들은 단일 tx 커넥션에서 안전하도록 {@code concatMap}으로 순차 실행한다.
     * <p>
     * <b>불변식:</b> flush(및 insert 경로)는 {@link #sqlExecutor}만 호출하고 public session-aware
     * 메서드(findById/findAll/save)를 절대 호출하지 않는다 — auto-flush 재진입을 막기 위함이다.
     */
    private Mono<Void> flush(PersistenceSession session) {
        return Mono.defer(() -> {
            if (session.isEmpty()) {
                return Mono.empty();
            }
            return Flux.fromIterable(new ArrayList<>(session.managedEntries()))
                    .concatMap(this::flushEntry)
                    .then();
        });
    }

    /**
     * managed 엔티티 1건을 flush한다. 스냅샷 대비 변경된 컬럼이 없으면 no-op. 변경이 있으면 기존
     * {@code update(entity, fields)}와 동일한 audit(@UpdatedAt)/리스너(@PreUpdate·@PostUpdate)/낙관락(@Version)
     * 코레오그래피로 부분 UPDATE를 발행하고 스냅샷을 갱신한다.
     */
    private Mono<Void> flushEntry(PersistenceSession.ManagedEntry entry) {
        return Mono.defer(() -> {
            Object entity = entry.entity();
            EntityMetadata<?> metadata = entry.metadata();
            if (entry.dirtyPropertyNames().isEmpty()) {
                return Mono.empty();
            }
            try {
                auditApplier.applyOnUpdate(entity, metadata);
                listenerInvoker.invokePreUpdate(entity, metadata);
            } catch (RuntimeException exception) {
                return Mono.error(exception);
            }
            // audit/@PreUpdate 콜백이 추가로 더럽힌 컬럼까지 재diff로 포착(@UpdatedAt 포함).
            LinkedHashSet<String> fields = new LinkedHashSet<>(entry.dirtyPropertyNames());
            if (fields.isEmpty()) {
                return Mono.empty();
            }
            PersistentProperty versionProperty = metadata.versionProperty().orElse(null);
            if (versionProperty != null) {
                fields.add(versionProperty.propertyName());
            }
            // @SecondaryTable: dirty 필드 중 primary 컬럼이 있으면 primary partial UPDATE를 발행한다. primary
            // 컬럼이 전혀 없으면(보조 컬럼만 dirty) primary UPDATE를 건너뛴다. 보조 테이블은 full UPDATE로 동기화.
            boolean hasPrimaryField = fields.stream()
                    .anyMatch(field -> metadata.findProperty(field).map(p -> !p.secondary()).orElse(false));
            Mono<Void> primaryUpdate;
            if (!hasPrimaryField) {
                primaryUpdate = Mono.empty();
            } else if (versionProperty == null) {
                SqlStatement statement = dialect.sqlRenderer().update(metadata, entity, fields);
                primaryUpdate = sqlExecutor.execute(statement).then();
            } else {
                SqlStatement statement = dialect.sqlRenderer().update(metadata, entity, fields);
                Object current = versionProperty.read(entity);
                Object next = nextVersionValue(versionProperty, current);
                primaryUpdate = sqlExecutor.execute(statement)
                        .flatMap(affected -> {
                            if (affected == 0L) {
                                return Mono.error(new OptimisticLockingFailureException(
                                        "Optimistic locking failure: row not found or version mismatch for "
                                                + metadata.entityType().getName()
                                                + " id=" + metadata.readIdValue(entity)
                                                + " version=" + current));
                            }
                            versionProperty.write(entity, next);
                            return Mono.just(affected);
                        })
                        .then();
            }
            // dirty 필드에 자기 컬럼이 포함된 보조 테이블만 갱신한다 — primary 컬럼만 dirty인 변경은 어떤 보조
            // 테이블도 건드리지 않아 불필요한 write를 제거한다(정확성 유지). version 컬럼은 primary이므로
            // 보조 테이블 dirty 판정에 영향을 주지 않는다.
            Mono<Void> withSecondary = metadata.hasSecondaryTables()
                    ? primaryUpdate.then(updateSecondaryRows(metadata, entity, fields))
                    : primaryUpdate;
            return withSecondary.doOnSuccess(ignored -> {
                listenerInvoker.invokePostUpdate(entity, metadata);
                entry.refreshSnapshot();
            });
        });
    }

    /**
     * annotation-driven 자동 hydration을 거치지 않고 단건 row만 발행하는 내부 경로.
     * {@code findById(.., FetchGroup)}처럼 merge된 group을 따로 hydrate할 호출자가 사용한다.
     */
    private <T, ID> Mono<T> findByIdInternal(EntityMetadata<T> metadata, ID id) {
        if (metadata.hasSecondaryTables()) {
            // @SecondaryTable: primary ⟕ 보조 테이블 LEFT JOIN으로 한 row를 조회한다(컬럼은 전역 유일하므로
            // 디코딩은 일반 mapRow가 그대로 처리한다).
            return sqlExecutor.queryOne(
                    dialect.sqlRenderer().selectByIdWithSecondaryTables(metadata, id),
                    row -> mapRow(metadata, row));
        }
        if (isMultiTableInheritance(metadata)) {
            return findByIdMultiTable(metadata, id);
        }
        EntityMetadata<?> render = renderMetadata(metadata);
        return sqlExecutor.queryOne(
                selectByIdStatement(render, id), row -> mapRowDispatching(metadata, render, row));
    }

    /**
     * 이 메타데이터가 JOINED 또는 TABLE_PER_CLASS 상속에 속하는지 — 즉 단일-테이블 SELECT 대신 멀티테이블
     * JOIN/UNION 경로를 타야 하는지 판정한다.
     */
    private static boolean isMultiTableInheritance(EntityMetadata<?> metadata) {
        if (!metadata.hasInheritance()) {
            return false;
        }
        InheritanceInfo info = metadata.inheritance();
        // JOINED는 루트/구체 모두 컬럼이 여러 테이블에 흩어져 있어 JOIN이 필요하다. TABLE_PER_CLASS는 각 구체
        // 타입이 모든 컬럼을 가진 독립 테이블이므로 구체 타입 조회는 자기 테이블만 보면 된다(UNION+client filter
        // 불필요) — 추상 루트 다형 조회만 UNION이 필요하다.
        if (info.joined()) {
            return true;
        }
        return info.tablePerClass() && metadata.isInheritanceRoot();
    }

    /**
     * 이 메타데이터가 TABLE_PER_CLASS 상속의 추상 루트인지 — 즉 자기 물리 테이블이 없고 구체 서브타입
     * 테이블들의 합집합으로만 조회/집계해야 하는지 판정한다.
     */
    private static boolean isTablePerClassRoot(EntityMetadata<?> metadata) {
        return metadata.hasInheritance()
                && metadata.inheritance().tablePerClass()
                && metadata.isInheritanceRoot();
    }

    /**
     * JOINED/TABLE_PER_CLASS findById — 루트 다형 SELECT(JOIN/UNION)로 한 row를 조회해 discriminator로 구체
     * 타입을 판별·인스턴스화한다. 구체 타입으로 조회한 경우(루트가 아닌 경우) 결과 타입이 일치하지 않으면
     * 빈 결과로 만든다(예: findById(Car, truckId)).
     */
    @SuppressWarnings("unchecked")
    private <T, ID> Mono<T> findByIdMultiTable(EntityMetadata<T> metadata, ID id) {
        InheritanceLayout layout = metadataFactory.inheritanceLayout(metadata.inheritance().root());
        SqlStatement statement = layout.info().joined()
                ? dialect.sqlRenderer().selectJoinedById(layout, id)
                : dialect.sqlRenderer().selectTablePerClassById(layout, id);
        Mono<Object> result = sqlExecutor.queryOne(
                statement, row -> (Object) mapRowForInheritance(layout, row));
        if (metadata.isInheritanceRoot()) {
            return result.map(entity -> (T) entity);
        }
        return result.filter(entity -> metadata.entityType().isInstance(entity)).map(entity -> (T) entity);
    }

    /**
     * 다형 row(JOIN/UNION 결과)에서 discriminator 값을 읽어 구체 서브타입을 해석하고 그 타입으로 매핑한다.
     */
    private Object mapRowForInheritance(InheritanceLayout layout, RowAccessor row) {
        InheritanceInfo info = layout.info();
        Object discriminator = row.get(info.discriminatorColumn(), wrapPrimitive(info.discriminatorJavaType()));
        EntityMetadata<?> concrete = metadataFactory.resolveSubtype(layout.rootMetadata(), discriminator);
        return mapRow(concrete, row);
    }

    /**
     * findById용 SELECT 문을 만든다. 단순 케이스(단일 {@code @Id}, 상속·soft-delete 없음)는 SQL 텍스트가
     * 엔티티마다 상수라 1회 렌더해 캐시하고 바인딩(id 값)만 새로 만든다 — 핫패스에서 select-list 문자열과
     * RenderContext를 매번 다시 만들던 비용을 제거한다. 그 외(복합키/상속 다형 제한/soft-delete-alive 가드)는
     * id 외 추가 조건이 SQL에 섞이므로 dialect 렌더러로 매번 정확히 렌더한다.
     */
    private SqlStatement selectByIdStatement(EntityMetadata<?> render, Object id) {
        if (render.hasCompositeId() || render.hasInheritance() || render.softDeleteProperty().isPresent()) {
            return dialect.sqlRenderer().selectById(render, id);
        }
        String sql = selectByIdSqlCache.computeIfAbsent(render,
                metadata -> dialect.sqlRenderer().selectById(metadata, id).sql());
        return new SqlStatement(sql, java.util.Collections.singletonList(render.idProperty().toColumnValue(id)));
    }

    /**
     * annotation-driven 자동 hydration을 거치지 않고 일반 SELECT만 발행하는 내부 경로.
     */
    private <T> Flux<T> findAllInternal(EntityMetadata<T> metadata, QuerySpec querySpec) {
        if (metadata.hasSecondaryTables()) {
            // @SecondaryTable: primary ⟕ 보조 테이블 LEFT JOIN으로 조회한다(predicate/sort는 primary 컬럼 기준).
            QuerySpec spec = normalize(querySpec);
            if (spec.lockMode() != LockMode.NONE) {
                // 비관락 + @SecondaryTable fail-fast. 보조 테이블 SELECT는 LEFT JOIN을 파생 테이블
                // (select * from (... left join ...) as nova_secondary)로 감싸므로, 거기에 FOR UPDATE/FOR SHARE를
                // 붙이면 PostgreSQL 등은 "FOR UPDATE cannot be applied to the nullable side of an outer join" 또는
                // 파생 테이블 락 거부로 런타임 에러를 낸다. 조용히 깨진 SQL을 던지는 대신 명확히 거부한다.
                return Flux.error(new UnsupportedOperationException(
                        "Pessimistic lock (" + spec.lockMode() + ") is not supported together with @SecondaryTable on "
                                + metadata.entityType().getName()
                                + ": the secondary-table SELECT wraps a LEFT JOIN in a derived table, which databases"
                                + " such as PostgreSQL refuse to lock. Remove the lock mode, or query the primary"
                                + " table without the secondary-table mapping for the locked read."));
            }
            return sqlExecutor.queryMany(
                    dialect.sqlRenderer().selectWithSecondaryTables(metadata, spec),
                    row -> mapRow(metadata, row));
        }
        if (isMultiTableInheritance(metadata)) {
            return findAllMultiTable(metadata, querySpec);
        }
        EntityMetadata<?> render = renderMetadata(metadata);
        return sqlExecutor.queryMany(
                dialect.sqlRenderer().select(render, normalize(querySpec)), row -> mapRowDispatching(metadata, render, row));
    }

    /**
     * JOINED/TABLE_PER_CLASS findAll — 루트 다형 SELECT(JOIN/UNION)로 전 서브타입 row를 조회해 각 row의
     * discriminator로 구체 타입을 인스턴스화한다. 구체 타입으로 조회한 경우 그 타입 인스턴스만 남긴다.
     */
    @SuppressWarnings("unchecked")
    private <T> Flux<T> findAllMultiTable(EntityMetadata<T> metadata, QuerySpec querySpec) {
        InheritanceLayout layout = metadataFactory.inheritanceLayout(metadata.inheritance().root());
        QuerySpec spec = normalize(querySpec);
        SqlStatement statement = layout.info().joined()
                ? dialect.sqlRenderer().selectJoinedPolymorphic(layout, spec)
                : dialect.sqlRenderer().selectTablePerClassPolymorphic(layout, spec);
        Flux<Object> rows = sqlExecutor.queryMany(
                statement, row -> (Object) mapRowForInheritance(layout, row));
        if (metadata.isInheritanceRoot()) {
            return rows.map(entity -> (T) entity);
        }
        return rows.filter(entity -> metadata.entityType().isInstance(entity)).map(entity -> (T) entity);
    }

    /**
     * SINGLE_TABLE 상속 루트를 조회할 때 사용할 렌더링 메타데이터를 고른다. 루트면 모든 서브타입 컬럼을
     * union한 병합 메타데이터를 써서 한 SELECT가 전 서브타입 컬럼을 담게 하고, 그 외에는 원본을 그대로 쓴다.
     */
    private EntityMetadata<?> renderMetadata(EntityMetadata<?> metadata) {
        return metadata.isInheritanceRoot()
                ? metadataFactory.mergedHierarchyMetadata(metadata.entityType())
                : metadata;
    }

    /**
     * row를 엔티티로 매핑하되, 조회 대상이 SINGLE_TABLE 상속 루트이면 row의 discriminator 값으로 구체
     * 서브타입을 판별해 해당 타입으로 인스턴스화한다. 루트가 아니면 선언 타입 그대로 매핑한다.
     */
    @SuppressWarnings("unchecked")
    private <T> T mapRowDispatching(EntityMetadata<T> declared, EntityMetadata<?> render, RowAccessor row) {
        if (!declared.isInheritanceRoot()) {
            return mapRow(declared, row);
        }
        InheritanceInfo info = render.inheritance();
        Object discriminator = row.get(info.discriminatorColumn(), wrapPrimitive(info.discriminatorJavaType()));
        EntityMetadata<?> concrete = metadataFactory.resolveSubtype(render, discriminator);
        return (T) mapRow(concrete, row);
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
        if (metadata.hasCompositeId()) {
            // 단일 컬럼 IN으로는 복합키를 표현할 수 없다. 개별 findById로 분기한다.
            return Flux.fromIterable(materialized).concatMap(id -> findById(entityType, id));
        }
        String idPropertyName = metadata.idProperty().propertyName();
        QuerySpec spec = QuerySpec.empty().where(Criteria.in(idPropertyName, materialized));
        if (metadata.hasSecondaryTables()) {
            // @SecondaryTable: LEFT JOIN SELECT 경로(findAllInternal)로 위임한다.
            return findAllInternal(metadata, spec);
        }
        if (isMultiTableInheritance(metadata)) {
            return findAllMultiTable(metadata, spec);
        }
        EntityMetadata<?> render = renderMetadata(metadata);
        return sqlExecutor.queryMany(
                dialect.sqlRenderer().select(render, spec), row -> mapRowDispatching(metadata, render, row));
    }

    @Override
    public <T> Mono<Long> delete(T entity) {
        EntityMetadata<T> metadata = metadataFactory.getEntityMetadata(entityType(entity));
        Object id = metadata.readIdValue(entity);
        if (id == null) {
            return Mono.error(new IllegalArgumentException("Entity id must not be null for delete"));
        }
        // soft-delete UPDATE 경로에서도 동일하게 @PreRemove를 호출해 hard/soft 차이를 콜백 관점에서는 숨긴다.
        try {
            listenerInvoker.invokePreRemove(entity, metadata);
        } catch (RuntimeException exception) {
            return Mono.error(exception);
        }
        // @OneToMany(cascade=REMOVE/ALL) 또는 orphanRemoval=true child를 parent 삭제 전에 먼저 삭제해 FK 의존성을
        // 만족시킨다. 전파할 관계가 없으면 무비용. 그 뒤 parent를 삭제한다(reactive 순서 보장).
        //
        // @ManyToOne/@OneToOne(cascade=REMOVE/ALL) 참조 엔티티는 반대로 owner 삭제 *후*에 삭제한다 — FK가 owner
        // row에 있으므로 owner가 먼저 사라져야 참조 row 삭제가 FK 의존성을 위반하지 않는다. 참조 id는 owner row가
        // 사라지기 전에 미리 수집해 둔다(entity 객체에서 동기적으로 읽음).
        return cascadeRemoveOneToManyChildren(metadata, id)
                .then(performDelete(metadata, entity, id))
                .doOnNext(affected -> listenerInvoker.invokePostRemove(entity, metadata))
                .flatMap(affected -> cascadeRemoveToOneReferences(metadata, entity).thenReturn(affected));
    }

    /**
     * {@code @ManyToOne(cascade=REMOVE/ALL)} 또는 owning {@code @OneToOne(cascade=REMOVE/ALL)}의 참조 엔티티를
     * owner 삭제 직후 삭제한다. owner row의 FK가 참조를 가리키므로 owner가 먼저 삭제돼야 참조 삭제가 FK 의존성을
     * 위반하지 않는다(@OneToMany child-before-parent와 반대 순서). 참조 필드가 null이거나 cascade-remove 관계가
     * 없으면 무비용. 참조 엔티티는 {@link #deleteById(Class, Object)}로 삭제해 listener/soft-delete 경로를 공유한다.
     */
    private <T> Mono<Void> cascadeRemoveToOneReferences(EntityMetadata<T> metadata, T owner) {
        List<PersistentProperty> removing = metadata.manyToOneProperties().stream()
                .filter(PersistentProperty::cascadeRemoveReference)
                .toList();
        if (removing.isEmpty()) {
            return Mono.empty();
        }
        return Flux.fromIterable(removing)
                .concatMap(property -> {
                    Object reference;
                    try {
                        reference = property.field().get(owner);
                    } catch (IllegalAccessException exception) {
                        return Mono.error(new IllegalStateException(
                                "Cannot read to-one reference " + property.propertyName()
                                        + " for cascade remove on " + metadata.entityType().getName(), exception));
                    }
                    if (reference == null) {
                        return Mono.empty();
                    }
                    // 선언 타입(manyToOneTargetType)이 아니라 실제 인스턴스 클래스로 삭제한다. 상속 관계에서
                    // 참조가 서브타입이면 선언 타입(특히 TABLE_PER_CLASS 추상 루트는 테이블이 없음)으로 삭제하면
                    // 잘못된/존재하지 않는 테이블을 친다. Nova는 lazy proxy가 없어 getClass()가 실제 엔티티 타입이다.
                    Class<?> referenceType = reference.getClass();
                    EntityMetadata<?> referenceMetadata = metadataFactory.getEntityMetadata(referenceType);
                    Object referenceId = referenceMetadata.readIdValue(reference);
                    if (referenceId == null) {
                        // id 없는 참조(미영속)면 삭제할 row가 없다 → no-op.
                        return Mono.empty();
                    }
                    return deleteById(referenceType, referenceId).then();
                })
                .then();
    }

    /**
     * parent 삭제 시 {@code @OneToMany(cascade=REMOVE/ALL)} 또는 {@code orphanRemoval=true} child를 child 측
     * mappedBy FK 컬럼으로 일괄 삭제한다. cascade-remove 관계가 없으면 무비용. parentId가 null이면 호출자가 이미
     * 가드했으므로 여기서는 비어 있지 않다고 가정한다.
     */
    private <T> Mono<Void> cascadeRemoveOneToManyChildren(EntityMetadata<T> metadata, Object parentId) {
        List<PersistentProperty> removing = metadata.oneToManyProperties().stream()
                .filter(property -> property.cascadeRemoveChildren() || property.orphanRemoval())
                .toList();
        if (removing.isEmpty()) {
            return Mono.empty();
        }
        return Flux.fromIterable(removing)
                .concatMap(property -> {
                    if (property.oneToManyTargetType() == null) {
                        return Mono.error(new IllegalStateException(
                                metadata.entityType().getName() + "." + property.propertyName()
                                        + " @OneToMany(cascade=REMOVE/orphanRemoval) requires targetEntity to be specified"));
                    }
                    EntityMetadata<?> childMetadata = metadataFactory.getEntityMetadata(property.oneToManyTargetType());
                    PersistentProperty mappedByProperty = resolveMappedByProperty(metadata, property, childMetadata);
                    QuerySpec spec = QuerySpec.empty()
                            .where(Criteria.eq(mappedByProperty.propertyName(), parentId));
                    return sqlExecutor.execute(dialect.sqlRenderer().deleteByQuery(childMetadata, spec));
                })
                .then();
    }

    /**
     * hard/soft delete 분기를 수행한다. {@code @PreRemove}는 호출 측에서 이미 발화했고, 성공적으로
     * 행이 영향받았을 때 {@code @PostRemove}는 이 {@code Mono}를 구독하는 {@link #delete(Object)}가 발화한다.
     * optimistic locking 실패는 {@code Mono.error}로 끝나므로 {@code @PostRemove}가 호출되지 않는다.
     */
    private <T> Mono<Long> performDelete(EntityMetadata<T> metadata, T entity, Object id) {
        if (metadata.hasInheritance() && metadata.inheritance().joined()) {
            return deleteJoined(metadata, id);
        }
        Mono<Long> core = performDeleteCore(metadata, entity, id);
        if (!metadata.hasSecondaryTables() || metadata.softDeleteProperty().isPresent()) {
            // 보조 테이블이 없거나 soft delete(primary 행을 논리적으로 보존)면 보조 행을 건드리지 않는다.
            return core;
        }
        // @SecondaryTable hard delete. 스키마 생성이 보조 테이블 PK 조인 컬럼 → primary PK의 enforced FK를
        // emit하므로(AbstractSchemaGenerator.createSecondaryTable) FK 의존성상 보조 행을 primary 행보다 먼저
        // 삭제해야 한다(child-before-parent). primary DELETE를 물리적으로 먼저 실행하면 보조 행이 남아 있어
        // FK 위반이 된다.
        Optional<PersistentProperty> version = metadata.versionProperty();
        if (version.isEmpty()) {
            // @Version 없음: 낙관락 실패 개념이 없으므로 기존 FK 안전 순서(보조 → primary)를 그대로 유지한다.
            return deleteSecondaryRows(metadata, id).then(core);
        }
        // @Version 보유: stale 엔티티로 삭제하면 primary version-checked DELETE가 0행 →
        // OptimisticLockingFailureException이 된다. 보조 행을 먼저 지운 뒤라면 비트랜잭션(autocommit)에서
        // 보조 행만 사라져 "락 실패면 무변경" 계약이 깨진다(데이터 손실). FK 때문에 primary DELETE를 보조
        // DELETE보다 먼저 발행할 수는 없으므로, 보조 행을 건드리기 전에 primary 행의 (id, version) 존재를
        // SELECT로 선검증한다. 선검증이 실패하면 어떤 DELETE도 발행하지 않아 보조 행이 보존된다. 통과하면
        // FK 안전 순서(보조 → primary)로 삭제하며, primary DELETE는 그 사이 동시 변경 대비로 여전히
        // version-checked다(좁은 race window에서도 primary는 절대 잘못된 version으로 지워지지 않는다).
        return ensurePrimaryVersionPresent(metadata, entity, id, version.get())
                .then(deleteSecondaryRows(metadata, id))
                .then(core);
    }

    /**
     * primary 테이블에 주어진 {@code id}와 entity의 현재 {@code @Version} 값을 가진 행이 존재하는지 COUNT로
     * 선검증한다. 존재하지 않으면(행 부재 또는 version 불일치) {@link OptimisticLockingFailureException}으로
     * 끝나 호출자가 어떤 DELETE도 발행하지 않게 한다. @SecondaryTable hard delete에서 보조 행을 먼저 지우기
     * 전에 호출돼, 낙관락 실패 시 보조 행이 보존되도록(=비트랜잭션 무변경 계약) 보장한다. id/version 모두
     * primary 테이블 컬럼이므로 보조 테이블을 조인하지 않는 primary-only COUNT다.
     */
    /**
     * 보조 행을 지우기 전에 primary 행이 기대 {@code @Version}으로 존재하는지 COUNT로 선검증한다. 호출자가 처음부터
     * stale version을 들고 delete하면 어떤 DELETE도 발행되지 않아 보조/primary가 보존된다.
     * <p><b>원자성 한계</b>: COUNT → 보조 DELETE → version-checked primary DELETE는 별개 문이므로, 트랜잭션
     * 밖(statement별 autocommit)에서는 COUNT와 primary DELETE 사이에 다른 actor가 version을 bump하면 보조만
     * 지워지고 primary DELETE가 실패하는 좁은 TOCTOU 구간이 남는다. versioned {@code @SecondaryTable} hard
     * delete의 "락 실패 ⇒ 무변경" 원자성은 호출을 트랜잭션 경계 안에서 수행할 때만 보장된다.
     */
    private Mono<Void> ensurePrimaryVersionPresent(
            EntityMetadata<?> metadata, Object entity, Object id, PersistentProperty versionProperty) {
        Object currentVersion = versionProperty.read(entity);
        QuerySpec spec = QuerySpec.empty().where(Criteria.and(
                Criteria.eq(metadata.idProperty().propertyName(), id),
                Criteria.eq(versionProperty.propertyName(), currentVersion)));
        return sqlExecutor.queryOne(
                        dialect.sqlRenderer().count(metadata, spec), row -> row.get("count", Long.class))
                .defaultIfEmpty(0L)
                .flatMap(count -> count > 0L
                        ? Mono.empty()
                        : Mono.error(new OptimisticLockingFailureException(
                                "Optimistic locking failure: row not found or version mismatch for "
                                        + metadata.entityType().getName()
                                        + " id=" + id
                                        + " version=" + currentVersion)));
    }

    private <T> Mono<Long> performDeleteCore(EntityMetadata<T> metadata, T entity, Object id) {
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

    /**
     * JOINED 상속 DELETE — 서브타입 테이블 row를 먼저 삭제하고(FK 의존성), 그 다음 루트 테이블 row를 삭제한다.
     * 영향 행 수는 루트 DELETE의 결과를 돌려준다(논리적으로 "엔티티 1건 삭제").
     */
    private Mono<Long> deleteJoined(EntityMetadata<?> metadata, Object id) {
        InheritanceLayout layout = metadataFactory.inheritanceLayout(metadata.inheritance().root());
        SqlRenderer renderer = dialect.sqlRenderer();
        Mono<Long> deleteSubtype = metadata.isInheritanceRoot()
                // 루트 타입으로 삭제 시 어느 서브타입 테이블에 있는지 모르므로 전 서브타입 테이블에서 시도한다.
                ? Flux.fromIterable(layout.subtypes())
                        .filter(subtype -> subtype.metadata().entityType() != layout.info().root())
                        .concatMap(subtype -> sqlExecutor.execute(
                                renderer.deleteJoinedSubtypeById(subtype.metadata(), id)))
                        .reduce(0L, Long::sum)
                : sqlExecutor.execute(renderer.deleteJoinedSubtypeById(metadata, id));
        return deleteSubtype.then(sqlExecutor.execute(renderer.deleteJoinedRootById(layout, id)));
    }

    @Override
    public <T, ID> Mono<Long> deleteById(Class<T> entityType, ID id) {
        Objects.requireNonNull(id, "id must not be null");
        EntityMetadata<T> metadata = metadataFactory.getEntityMetadata(entityType);
        if (metadata.hasInheritance() && metadata.inheritance().joined()) {
            return deleteJoined(metadata, id);
        }
        Optional<PersistentProperty> softDelete = metadata.softDeleteProperty();
        if (softDelete.isPresent()) {
            Object deletedAt = currentTimeFor(softDelete.get());
            return sqlExecutor.execute(dialect.sqlRenderer().softDeleteById(metadata, id, deletedAt));
        }
        if (metadata.hasSecondaryTables()) {
            // @SecondaryTable hard delete: 보조 테이블 행을 먼저 삭제(FK 의존성) 후 primary 행을 삭제한다.
            return deleteSecondaryRows(metadata, id)
                    .then(sqlExecutor.execute(dialect.sqlRenderer().deleteById(metadata, id)));
        }
        return sqlExecutor.execute(dialect.sqlRenderer().deleteById(metadata, id));
    }

    @Override
    public <T> Mono<Long> count(Class<T> entityType, QuerySpec querySpec) {
        EntityMetadata<T> metadata = metadataFactory.getEntityMetadata(entityType);
        QuerySpec spec = normalize(querySpec);
        if (isTablePerClassRoot(metadata)) {
            // TPC 추상 루트는 자기 물리 테이블이 없으므로 구체 서브타입 테이블들의 count를 합산한다
            // (predicate는 모든 구체 테이블에 존재하는 루트 컬럼 기준).
            InheritanceLayout layout = metadataFactory.inheritanceLayout(metadata.inheritance().root());
            return Flux.fromIterable(layout.subtypes())
                    .concatMap(sub -> sqlExecutor.queryOne(
                            dialect.sqlRenderer().count(sub.metadata(), spec), row -> row.get("count", Long.class)))
                    .reduce(0L, Long::sum);
        }
        return sqlExecutor.queryOne(dialect.sqlRenderer().count(metadata, spec), row -> row.get("count", Long.class));
    }

    @Override
    public <T> Mono<Boolean> exists(Class<T> entityType, QuerySpec querySpec) {
        EntityMetadata<T> metadata = metadataFactory.getEntityMetadata(entityType);
        QuerySpec spec = normalize(querySpec);
        if (isTablePerClassRoot(metadata)) {
            InheritanceLayout layout = metadataFactory.inheritanceLayout(metadata.inheritance().root());
            return Flux.fromIterable(layout.subtypes())
                    .concatMap(sub -> sqlExecutor.queryOne(
                            dialect.sqlRenderer().exists(sub.metadata(), spec), row -> Boolean.TRUE))
                    .hasElements();
        }
        return sqlExecutor.queryOne(dialect.sqlRenderer().exists(metadata, spec), row -> Boolean.TRUE)
                .defaultIfEmpty(Boolean.FALSE);
    }

    /**
     * SELECT(LIMIT/OFFSET 적용) 한 번과 COUNT(*) 한 번을 병렬로 발행해 {@link Page}로 합친다.
     * COUNT 경로에서는 호출자 또는 normalize가 부착했던 pageable을 제거해 predicate 전체에 대한
     * 정확한 행 수를 계산한다 — 그렇지 않으면 LIMIT으로 잘린 행 수만 세어 totalElements가 잘못된다.
     */
    @Override
    public <T> Mono<Page<T>> findAll(Class<T> entityType, QuerySpec querySpec, Pageable pageable) {
        Objects.requireNonNull(entityType, "entityType must not be null");
        Objects.requireNonNull(pageable, "pageable must not be null");
        QuerySpec normalized = normalize(querySpec);
        QuerySpec paged = normalized.page(pageable);
        QuerySpec countSpec = normalized.page(null);
        return Mono.zip(
                findAll(entityType, paged).collectList(),
                count(entityType, countSpec),
                (content, total) -> new Page<>(content, total, pageable));
    }

    /**
     * 한 페이지보다 1행 더 조회한 뒤 초과 행이 있으면 {@code hasNext=true}로 표시하고
     * {@code content}는 정확히 {@code pageable.limit()}개로 잘라 {@link Slice}로 발행한다.
     * 총 행 수 쿼리는 발행하지 않으므로 비용이 낮다.
     */
    @Override
    public <T> Mono<Slice<T>> findSlice(Class<T> entityType, QuerySpec querySpec, Pageable pageable) {
        Objects.requireNonNull(entityType, "entityType must not be null");
        Objects.requireNonNull(pageable, "pageable must not be null");
        Pageable probe = Pageable.of(pageable.limit() + 1, pageable.offset());
        QuerySpec paged = normalize(querySpec).page(probe);
        return findAll(entityType, paged).collectList().map(list -> {
            boolean hasNext = list.size() > pageable.limit();
            List<T> trimmed = hasNext ? List.copyOf(list.subList(0, pageable.limit())) : list;
            return new Slice<>(trimmed, pageable, hasNext);
        });
    }

    @Override
    public <R> Mono<R> inTransaction(Function<ReactiveEntityOperations, Mono<R>> callback) {
        return transactionOperations.inTransaction(ignored -> Mono.deferContextual(ctx -> {
            if (!sessionEnabled || ctx.hasKey(SESSION_KEY)) {
                // 세션 비활성(kill-switch) 또는 중첩 inTransaction/savepoint: 외부 세션을 공유하고
                // 새 세션·flush를 만들지 않는다(최외곽 스코프만 flush를 소유).
                return callback.apply(this);
            }
            PersistenceSession session = new PersistenceSession();
            // flush를 콜백의 마지막 단계로 끼워 tx 레이어의 commit이 그 뒤에 붙게 한다 = flush-before-commit.
            return callback.apply(this)
                    .flatMap(result -> flush(session).thenReturn(result))
                    .switchIfEmpty(Mono.defer(() -> flush(session).then(Mono.empty())))
                    .contextWrite(context -> context.put(SESSION_KEY, session));
        }));
    }

    @Override
    public <R> Mono<R> inReadSession(Function<ReactiveEntityOperations, Mono<R>> callback) {
        // 커넥션 스코프를 지원하는 배선이면 단일 커넥션을 묶어 per-op acquire를 제거한다. 아니면(예: 커넥션을
        // Context에 싣지 않는 배선) 콜백을 그대로 실행해 현행 동작으로 안전 폴백한다. 트랜잭션/세션은 켜지 않는다.
        if (transactionOperations instanceof io.nova.tx.ReactiveConnectionOperations connectionOperations) {
            return connectionOperations.withConnection(Mono.defer(() -> callback.apply(this)));
        }
        return callback.apply(this);
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
            Object id = metadata.readIdValue(entity);
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
    public <P> Mono<P> findById(Class<P> entityType, Object id, FetchGroup<P> fetchGroup) {
        Objects.requireNonNull(entityType, "entityType must not be null");
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(fetchGroup, "fetchGroup must not be null");
        if (!entityType.equals(fetchGroup.parentType())) {
            return Mono.error(new IllegalArgumentException(
                    "FetchGroup parent type " + fetchGroup.parentType().getName()
                            + " does not match call entityType " + entityType.getName()));
        }
        EntityMetadata<P> metadata = metadataFactory.getEntityMetadata(entityType);
        FetchGroup<P> merged = mergeWithAnnotationGroup(entityType, fetchGroup, metadata);
        return findByIdInternal(metadata, id)
                .flatMap(parent -> hydrateChildren(List.of(parent), merged).thenReturn(parent));
    }

    @Override
    public <P> Flux<P> findAll(Class<P> entityType, FetchGroup<P> fetchGroup) {
        Objects.requireNonNull(entityType, "entityType must not be null");
        Objects.requireNonNull(fetchGroup, "fetchGroup must not be null");
        if (!entityType.equals(fetchGroup.parentType())) {
            return Flux.error(new IllegalArgumentException(
                    "FetchGroup parent type " + fetchGroup.parentType().getName()
                            + " does not match call entityType " + entityType.getName()));
        }
        EntityMetadata<P> metadata = metadataFactory.getEntityMetadata(entityType);
        FetchGroup<P> merged = mergeWithAnnotationGroup(entityType, fetchGroup, metadata);
        return findAllInternal(metadata, QuerySpec.empty())
                .collectList()
                .flatMapMany(parents -> hydrateChildren(parents, merged).thenMany(Flux.fromIterable(parents)));
    }

    /**
     * 사용자가 명시한 {@link FetchGroup}과 entity의 {@code @ManyToOne}/{@code @OneToMany} 어노테이션에서
     * 도출된 group을 merge한다. 같은 {@code (childType, childForeignKeyColumn)} 페어가 양쪽에 모두 있으면
     * 사용자 spec이 우선한다 — 사용자가 명시적으로 정의한 setter/extractor가 annotation 기본 동작을 override할 수
     * 있게 하기 위해서다. 관계 어노테이션이 없는 entity는 user group을 그대로 반환한다.
     */
    private <P> FetchGroup<P> mergeWithAnnotationGroup(
            Class<P> entityType, FetchGroup<P> userGroup, EntityMetadata<P> metadata) {
        if (!metadata.hasRelationProperties()) {
            return userGroup;
        }
        FetchGroup<P> annotationGroup = annotationFetchGroupBuilder.buildFor(entityType);
        if (annotationGroup.specs().isEmpty()) {
            return userGroup;
        }
        LinkedHashSet<String> userKeys = new LinkedHashSet<>();
        for (FetchGroup.FetchSpec<P, ?> spec : userGroup.specs()) {
            userKeys.add(specKey(spec));
        }
        FetchGroup.Builder<P> builder = FetchGroup.forParents(entityType);
        for (FetchGroup.FetchSpec<P, ?> spec : userGroup.specs()) {
            appendSpec(builder, spec);
        }
        for (FetchGroup.FetchSpec<P, ?> spec : annotationGroup.specs()) {
            if (userKeys.contains(specKey(spec))) {
                continue;
            }
            appendSpec(builder, spec);
        }
        return builder.build();
    }

    private static String specKey(FetchGroup.FetchSpec<?, ?> spec) {
        return spec.childType().getName() + "::" + spec.childForeignKeyColumn();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <P, C> void appendSpec(FetchGroup.Builder<P> builder, FetchGroup.FetchSpec<P, C> spec) {
        // builder는 single 플래그가 있는 spec을 직접 받는 공개 API가 없으므로, builder를 reflection 없이
        // 우회 — single 여부에 따라 적절한 with/withReferencedParent를 호출한다. single인 경우는
        // setter가 list → first-element wrapping을 이미 거친 BiConsumer이므로, 같은 list-setter를
        // 그대로 호출하는 with(...)를 사용해도 동작 의미가 보존된다.
        if (spec.single()) {
            // single setter를 위한 builder 진입점은 withReferencedParent — 단, 그 메서드는 새 list→single
            // adapter를 다시 씌우므로 여기서는 그대로 list-setter를 갖고 있는 spec을 보존하기 위해
            // 동일 setter를 single 어댑터로 재구성한다. spec.setter()는 이미 list→single로 풀어주는 setter이므로,
            // withReferencedParent가 새로 씌우는 list→single adapter를 통해 빈 리스트가 null로 변환되어
            // 같은 동작이 보존된다.
            BiConsumer<P, C> singleSetter = (parent, child) ->
                    spec.setter().accept(parent, child == null ? List.of() : List.of(child));
            builder.withReferencedParent(
                    spec.childType(),
                    spec.childForeignKeyColumn(),
                    spec.parentIdExtractor(),
                    singleSetter
            );
        } else {
            builder.with(
                    spec.childType(),
                    spec.childForeignKeyColumn(),
                    spec.parentIdExtractor(),
                    spec.setter(),
                    spec.orderBy()
            );
        }
    }

    /**
     * 주어진 parent 리스트에 대해 {@link FetchGroup}의 각 child spec을 IN-query로 한 번씩 실행하고
     * parent id 기준으로 그룹화해 setter로 주입한다. parents가 비어 있으면 child query는 건너뛴다.
     * <p>
     * parent id가 {@code null}인 parent는 child key 비교에서 제외되며, 해당 parent에는 빈 child 리스트가
     * 주입된다 — silent drop 대신 명시적으로 빈 결과로 setter를 호출해 호출자가 일관된 상태를 보게 한다.
     */
    private <P> Mono<Void> hydrateChildren(List<P> parents, FetchGroup<P> fetchGroup) {
        if (parents.isEmpty() || fetchGroup.specs().isEmpty()) {
            return Mono.empty();
        }
        return Flux.fromIterable(fetchGroup.specs())
                .concatMap(spec -> hydrateChildSpec(parents, spec))
                .then();
    }

    /**
     * {@code @ManyToMany} 컬렉션을 2-hop으로 hydration한다 — (1) link table을 owner FK IN으로 조회해
     * (owner→target id) 매핑을 얻고, (2) target을 id IN 단건 쿼리로 로드한 뒤 parent별로 그룹핑해 주입한다.
     * M2M property당 IN-query 2회로 N+1을 피한다. owning/inverse 모두 같은 경로다(inverse는 컬럼이 swap돼
     * ownerForeignKeyColumn이 항상 이 parent를 가리킨다).
     */
    private <P> Mono<Void> hydrateManyToMany(List<P> parents, EntityMetadata<P> metadata) {
        List<PersistentProperty> properties = metadata.manyToManyProperties();
        if (parents.isEmpty() || properties.isEmpty()) {
            return Mono.empty();
        }
        return Flux.fromIterable(properties)
                .concatMap(property -> hydrateOneManyToMany(parents, metadata, property))
                .then();
    }

    private <P> Mono<Void> hydrateOneManyToMany(
            List<P> parents, EntityMetadata<P> metadata, PersistentProperty property) {
        ManyToManyInfo info = property.manyToManyInfo();
        EntityMetadata<?> targetMetadata = metadataFactory.getEntityMetadata(info.targetType());
        JoinTableDefinition definition = joinDefinition(metadata, info, targetMetadata);
        PersistentProperty parentIdProperty = metadata.idProperty();
        Class<?> ownerIdType = wrapPrimitive(parentIdProperty.javaType());
        Class<?> targetIdType = wrapPrimitive(targetMetadata.idProperty().javaType());

        LinkedHashMap<Object, List<P>> parentsById = new LinkedHashMap<>();
        for (P parent : parents) {
            Object id = parentIdProperty.read(parent);
            if (id != null) {
                parentsById.computeIfAbsent(id, key -> new ArrayList<>()).add(parent);
            }
        }
        if (parentsById.isEmpty()) {
            injectEmptyCollections(property, parents, info.usesSet());
            return Mono.empty();
        }
        SqlRenderer renderer = dialect.sqlRenderer();
        List<Object> ownerIds = new ArrayList<>(parentsById.keySet());
        return sqlExecutor.queryMany(
                        renderer.selectJoinRows(definition, ownerIds),
                        row -> new Object[]{
                                row.get(info.ownerForeignKeyColumn(), ownerIdType),
                                row.get(info.targetForeignKeyColumn(), targetIdType)})
                .collectList()
                .flatMap(links -> {
                    LinkedHashMap<Object, List<Object>> targetIdsByOwner = new LinkedHashMap<>();
                    LinkedHashSet<Object> allTargetIds = new LinkedHashSet<>();
                    for (Object[] link : links) {
                        targetIdsByOwner.computeIfAbsent(link[0], key -> new ArrayList<>()).add(link[1]);
                        allTargetIds.add(link[1]);
                    }
                    if (allTargetIds.isEmpty()) {
                        injectEmptyCollections(property, parents, info.usesSet());
                        return Mono.empty();
                    }
                    String targetIdName = targetMetadata.idProperty().propertyName();
                    return findAllInternal(targetMetadata,
                                    QuerySpec.empty().where(Criteria.in(targetIdName, new ArrayList<>(allTargetIds))))
                            .collectList()
                            .doOnNext(targets -> {
                                Map<Object, Object> targetById = new LinkedHashMap<>();
                                for (Object target : targets) {
                                    targetById.put(targetMetadata.idProperty().read(target), target);
                                }
                                for (Map.Entry<Object, List<P>> entry : parentsById.entrySet()) {
                                    List<Object> resolved = new ArrayList<>();
                                    for (Object targetId : targetIdsByOwner.getOrDefault(entry.getKey(), List.of())) {
                                        Object target = targetById.get(targetId);
                                        if (target != null) {
                                            resolved.add(target);
                                        }
                                    }
                                    for (P parent : entry.getValue()) {
                                        injectCollection(property, parent, resolved, info.usesSet());
                                    }
                                }
                            })
                            .then();
                });
    }

    private static <P> void injectEmptyCollections(PersistentProperty property, List<P> parents, boolean usesSet) {
        for (P parent : parents) {
            injectCollection(property, parent, List.of(), usesSet);
        }
    }

    private static void injectCollection(PersistentProperty property, Object parent, List<?> items, boolean usesSet) {
        Object value = usesSet ? new LinkedHashSet<>(items) : new ArrayList<>(items);
        try {
            property.field().set(parent, value);
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("Cannot inject collection " + property.propertyName(), exception);
        }
    }

    private static <P> void injectEmptyMaps(PersistentProperty property, List<P> parents) {
        for (P parent : parents) {
            injectMap(property, parent, new LinkedHashMap<>());
        }
    }

    private static void injectMap(PersistentProperty property, Object parent, Map<?, ?> entries) {
        try {
            property.field().set(parent, new LinkedHashMap<>(entries));
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("Cannot inject @ElementCollection Map " + property.propertyName(), exception);
        }
    }

    /**
     * {@code @ElementCollection} 값 컬렉션을 1-hop으로 hydration한다 — collection table을 owner FK IN으로 조회해
     * owner별 값 리스트를 모아 주입한다(원소가 엔티티가 아니라 기본 타입이므로 second hop 불필요). property당 IN-query 1회.
     */
    private <P> Mono<Void> hydrateElementCollections(List<P> parents, EntityMetadata<P> metadata) {
        List<PersistentProperty> collections = metadata.elementCollectionProperties();
        if (parents.isEmpty() || collections.isEmpty()) {
            return Mono.empty();
        }
        return Flux.fromIterable(collections)
                .concatMap(property -> hydrateOneElementCollection(parents, metadata, property))
                .then();
    }

    private <P> Mono<Void> hydrateOneElementCollection(
            List<P> parents, EntityMetadata<P> metadata, PersistentProperty property) {
        ElementCollectionInfo info = property.elementCollectionInfo();
        if (info.map()) {
            return hydrateOneMapCollection(parents, metadata, property);
        }
        CollectionTableDefinition definition = collectionDefinition(metadata, info);
        PersistentProperty parentIdProperty = metadata.idProperty();
        Class<?> ownerIdType = wrapPrimitive(parentIdProperty.javaType());
        Class<?> valueType = info.valueType();

        LinkedHashMap<Object, List<P>> parentsById = new LinkedHashMap<>();
        for (P parent : parents) {
            Object id = parentIdProperty.read(parent);
            if (id != null) {
                parentsById.computeIfAbsent(id, key -> new ArrayList<>()).add(parent);
            }
        }
        if (parentsById.isEmpty()) {
            injectEmptyCollections(property, parents, info.usesSet());
            return Mono.empty();
        }
        SqlRenderer renderer = dialect.sqlRenderer();
        List<Object> ownerIds = new ArrayList<>(parentsById.keySet());
        boolean embeddable = info.embeddable();
        return sqlExecutor.queryMany(
                        renderer.selectCollectionRows(definition, ownerIds),
                        row -> {
                            Object ownerKey = row.get(info.ownerForeignKeyColumn(), ownerIdType);
                            Object element = embeddable
                                    ? instantiateEmbeddableElement(info, row)
                                    : row.get(info.valueColumn(), valueType);
                            return new Object[]{ownerKey, element};
                        })
                .collectList()
                .doOnNext(rows -> {
                    LinkedHashMap<Object, List<Object>> valuesByOwner = new LinkedHashMap<>();
                    for (Object[] row : rows) {
                        valuesByOwner.computeIfAbsent(row[0], key -> new ArrayList<>()).add(row[1]);
                    }
                    for (Map.Entry<Object, List<P>> entry : parentsById.entrySet()) {
                        List<Object> values = valuesByOwner.getOrDefault(entry.getKey(), List.of());
                        for (P parent : entry.getValue()) {
                            injectCollection(property, parent, values, info.usesSet());
                        }
                    }
                })
                .then();
    }

    /**
     * {@code @ElementCollection Map<K,V>}을 1-hop으로 hydration한다 — collection table을 owner FK IN으로 조회해
     * (owner FK, key, value) 행을 owner별 {@code Map}으로 모아 주입한다. key는 저장 표현에서 도메인 타입으로
     * 디코딩하고(enum 이름/ordinal → 상수), value는 기본 타입/{@code @Embeddable}을 그대로 재사용한다.
     */
    private <P> Mono<Void> hydrateOneMapCollection(
            List<P> parents, EntityMetadata<P> metadata, PersistentProperty property) {
        ElementCollectionInfo info = property.elementCollectionInfo();
        CollectionTableDefinition definition = collectionDefinition(metadata, info);
        PersistentProperty parentIdProperty = metadata.idProperty();
        Class<?> ownerIdType = wrapPrimitive(parentIdProperty.javaType());
        Class<?> valueType = info.valueType();
        Class<?> keyColumnType = info.mapKey().keyColumnType();

        LinkedHashMap<Object, List<P>> parentsById = new LinkedHashMap<>();
        for (P parent : parents) {
            Object id = parentIdProperty.read(parent);
            if (id != null) {
                parentsById.computeIfAbsent(id, key -> new ArrayList<>()).add(parent);
            }
        }
        if (parentsById.isEmpty()) {
            injectEmptyMaps(property, parents);
            return Mono.empty();
        }
        SqlRenderer renderer = dialect.sqlRenderer();
        List<Object> ownerIds = new ArrayList<>(parentsById.keySet());
        boolean embeddable = info.embeddable();
        return sqlExecutor.queryMany(
                        renderer.selectCollectionRows(definition, ownerIds),
                        row -> {
                            Object ownerKey = row.get(info.ownerForeignKeyColumn(), ownerIdType);
                            Object mapKey = decodeMapKey(info, row.get(info.mapKey().keyColumn(), keyColumnType));
                            Object element = embeddable
                                    ? instantiateEmbeddableElement(info, row)
                                    : row.get(info.valueColumn(), valueType);
                            return new Object[]{ownerKey, mapKey, element};
                        })
                .collectList()
                .doOnNext(rows -> {
                    LinkedHashMap<Object, LinkedHashMap<Object, Object>> mapsByOwner = new LinkedHashMap<>();
                    for (Object[] row : rows) {
                        mapsByOwner.computeIfAbsent(row[0], key -> new LinkedHashMap<>()).put(row[1], row[2]);
                    }
                    for (Map.Entry<Object, List<P>> entry : parentsById.entrySet()) {
                        Map<Object, Object> entries = mapsByOwner.getOrDefault(entry.getKey(), new LinkedHashMap<>());
                        for (P parent : entry.getValue()) {
                            injectMap(property, parent, entries);
                        }
                    }
                })
                .then();
    }

    /**
     * {@code @Embeddable} 원소 타입의 인스턴스를 collection table row에서 만든다 — no-arg 생성자로 인스턴스화한 뒤
     * 펼친 각 컬럼 값을 해당 필드에 바인딩한다. {@link ElementCollectionInfo#valueType()}이 원소 타입이다.
     */
    private static Object instantiateEmbeddableElement(ElementCollectionInfo info, RowAccessor row) {
        Object element;
        try {
            java.lang.reflect.Constructor<?> constructor = info.valueType().getDeclaredConstructor();
            constructor.setAccessible(true);
            element = constructor.newInstance();
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(
                    "@Embeddable @ElementCollection element type " + info.valueType().getName()
                            + " must expose a no-args constructor", exception);
        }
        for (ElementCollectionInfo.EmbeddableColumn column : info.embeddableColumns()) {
            Object value = row.get(column.columnName(), column.columnType());
            try {
                column.field().set(element, value);
            } catch (IllegalAccessException exception) {
                throw new IllegalStateException(
                        "Cannot set @Embeddable @ElementCollection field " + column.field().getName(), exception);
            }
        }
        return element;
    }

    private <P, C> Mono<Void> hydrateChildSpec(List<P> parents, FetchGroup.FetchSpec<P, C> spec) {
        LinkedHashSet<Object> parentIds = new LinkedHashSet<>();
        for (P parent : parents) {
            Object key = spec.parentIdExtractor().apply(parent);
            if (key != null) {
                parentIds.add(key);
            }
        }
        if (parentIds.isEmpty()) {
            // null id로만 이루어진 parents — child query를 건너뛰고 모든 parent에 빈 리스트를 주입한다.
            for (P parent : parents) {
                spec.setter().accept(parent, List.of());
            }
            return Mono.empty();
        }
        EntityMetadata<C> childMetadata = metadataFactory.getEntityMetadata(spec.childType());
        PersistentProperty fkProperty = findPropertyByColumnName(childMetadata, spec.childForeignKeyColumn());
        QuerySpec querySpec = QuerySpec.empty().where(Criteria.in(fkProperty.propertyName(), new ArrayList<>(parentIds)));
        if (spec.orderBy() != null) {
            // @OneToMany(@OrderBy)로 지정된 child 정렬을 IN-query에 적용한다.
            querySpec = querySpec.orderBy(spec.orderBy());
        }
        // child fetch는 내부 경로로만 수행해 cyclical 관계가 무한 재귀를 일으키지 않게 한다.
        // 호출자가 child entity의 추가 관계까지 자동으로 hydrate되길 원하면 명시적 FetchGroup을 별도로 추가해야 한다.
        List<Object> orderedParentIds = new ArrayList<>(parentIds);
        return findAllInternal(childMetadata, querySpec)
                .collectList()
                .flatMap(children -> {
                    if (spec.orderColumn() == null) {
                        assignChildrenToParents(parents, children, spec, fkProperty);
                        return Mono.empty();
                    }
                    // @OrderColumn @OneToMany: 순서 컬럼은 child 엔티티 property가 아니므로 property 기반 ORDER BY로
                    // 정렬할 수 없다. child 테이블의 (PK, order) 행을 한 번 더 조회해 order 값으로 child를 정렬한 뒤
                    // FK 그룹화한다. 전역 order 오름차순 정렬을 안정 그룹화하면 각 parent 버킷이 0..n-1 순서가 된다.
                    return sortChildrenByOrderColumn(childMetadata, spec, orderedParentIds, children)
                            .doOnNext(sorted -> assignChildrenToParents(parents, sorted, spec, fkProperty))
                            .then();
                })
                .then();
    }

    /**
     * {@code @OrderColumn} @OneToMany fetch 정렬: child 테이블의 (PK, order) 행을 조회해 child를 order 컬럼
     * 오름차순으로 정렬한 리스트를 만든다. order 값이 없는(NULL) child는 끝으로 보낸다(안정 정렬).
     */
    private <C> Mono<List<C>> sortChildrenByOrderColumn(
            EntityMetadata<C> childMetadata, FetchGroup.FetchSpec<?, C> spec,
            List<Object> parentIds, List<C> children) {
        if (children.isEmpty()) {
            return Mono.just(children);
        }
        PersistentProperty childIdProperty = childMetadata.idProperty();
        Class<?> childIdType = wrapPrimitive(childIdProperty.javaType());
        SqlStatement statement = dialect.sqlRenderer().selectOneToManyOrder(
                childMetadata, spec.childForeignKeyColumn(), spec.orderColumn(), parentIds);
        return sqlExecutor.queryMany(statement,
                        row -> new Object[]{
                                row.get(childIdProperty.columnName(), childIdType),
                                row.get(spec.orderColumn(), Integer.class)})
                .collectList()
                .map(orderRows -> {
                    Map<Object, Integer> orderByChildId = new LinkedHashMap<>();
                    for (Object[] orderRow : orderRows) {
                        if (orderRow[1] != null) {
                            orderByChildId.put(orderRow[0], ((Number) orderRow[1]).intValue());
                        }
                    }
                    List<C> sorted = new ArrayList<>(children);
                    sorted.sort(java.util.Comparator.comparingInt(child ->
                            orderByChildId.getOrDefault(childIdProperty.read(child), Integer.MAX_VALUE)));
                    return sorted;
                });
    }

    /**
     * child fetch 결과를 parent id 기준으로 그룹화해 setter로 주입한다. parent id가 {@code null}이거나
     * 매칭되는 child가 없는 parent에는 빈 리스트가 주입된다.
     */
    private <P, C> void assignChildrenToParents(
            List<P> parents,
            List<C> children,
            FetchGroup.FetchSpec<P, C> spec,
            PersistentProperty fkProperty
    ) {
        LinkedHashMap<Object, List<C>> grouped = new LinkedHashMap<>();
        for (C child : children) {
            Object fk = fkProperty.read(child);
            if (fk == null) {
                continue;
            }
            grouped.computeIfAbsent(fk, ignored -> new ArrayList<>()).add(child);
        }
        for (P parent : parents) {
            Object key = spec.parentIdExtractor().apply(parent);
            List<C> bucket = key == null ? List.of() : grouped.getOrDefault(key, List.of());
            spec.setter().accept(parent, bucket);
        }
    }

    private PersistentProperty findPropertyByColumnName(EntityMetadata<?> metadata, String columnName) {
        for (PersistentProperty property : metadata.columnMappedProperties()) {
            if (property.columnName().equals(columnName)) {
                return property;
            }
        }
        throw new IllegalArgumentException(
                "No property maps to column '" + columnName + "' on " + metadata.entityType().getName());
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
        if (metadata.hasInheritance()
                && (metadata.inheritance().joined() || metadata.inheritance().tablePerClass())) {
            // JOINED/TABLE_PER_CLASS는 멀티테이블 INSERT/UPDATE 순서가 필요해 단일 batch SQL로 묶을 수 없다.
            // 단건 save() 경로로 폴백한다(insertJoined/updateJoined가 올바른 멀티테이블 순서를 보장).
            return Flux.fromIterable(entities).concatMap(this::save);
        }
        if (!key.isNew() && metadata.versionProperty().isPresent()) {
            // optimistic locking은 entity별 affected rows 검증이 필요해 batch 경로로 묶을 수 없다.
            return Flux.fromIterable(entities).concatMap(this::save);
        }
        if (key.isNew() && metadata.idProperty().generationType() == GenerationType.SEQUENCE) {
            // SEQUENCE는 엔티티별 nextval 조회가 필요해 batch로 묶을 수 없다.
            return Flux.fromIterable(entities).concatMap(this::save);
        }
        if (key.isNew() && metadata.idProperty().generationType() == GenerationType.TABLE) {
            // TABLE은 엔티티별 generator 테이블 increment가 필요해 batch로 묶을 수 없다(SEQUENCE와 동일).
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
                    .thenMany(Flux.fromIterable(entities))
                    .doOnNext(saved -> invokePostSave(key.isNew(), saved, metadata));
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
                    }))
                    .doOnNext(saved -> invokePostSave(key.isNew(), saved, metadata));
        }
        return sqlExecutor.executeBatch(sharedSql, bindingsList)
                .thenMany(Flux.fromIterable(entities))
                .doOnNext(saved -> invokePostSave(key.isNew(), saved, metadata));
    }

    /**
     * batch save 성공 후 그룹 종류에 따라 {@code @PostPersist}(insert) 또는 {@code @PostUpdate}(update)를
     * entity별로 발화한다.
     */
    private <T> void invokePostSave(boolean isNew, T entity, EntityMetadata<T> metadata) {
        if (isNew) {
            listenerInvoker.invokePostPersist(entity, metadata);
        } else {
            listenerInvoker.invokePostUpdate(entity, metadata);
        }
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
        // top-level(non-embedded) property는 즉시 entity에 주입한다. embedded property는 buffer에 모아
        // 호스트 path별로 all-null 여부를 판정한 뒤 entity에 반영한다. nested @Embedded에서도
        // outer host 전체가 all-null이면 outer까지 null로 설정해 빈 인스턴스가 남지 않도록 한다.
        List<EmbeddedValue> embeddedValues = new ArrayList<>();
        for (PersistentProperty property : metadata.columnMappedProperties()) {
            // converter가 있는 property(@Json, @Enumerated)는 driver가 디코딩 가능한 저장 타입(columnType)을
            // 요청해야 한다 — driver는 varchar 컬럼을 enum/POJO로 직접 디코딩할 수 없다. converter가 없으면
            // columnType()이 javaType을 그대로 돌려준다. primitive Java 타입을 그대로 row.get(..., type)에
            // 넘기면 일부 R2DBC driver(예: r2dbc-h2)가 "Cannot decode value of type boolean/long/..."으로
            // 거부하므로 boxed wrapper로 변환한다. entity 필드 주입 시점에는 reflection이 boxed → primitive
            // unboxing을 자동 처리한다.
            Object raw = row.get(property.columnName(), wrapPrimitive(property.columnType()));
            Object value = property.toPropertyValue(raw);
            if (property.embedded()) {
                embeddedValues.add(new EmbeddedValue(property, value));
                continue;
            }
            property.write(instance, value);
        }
        hydrateEmbeddedValues(instance, embeddedValues);
        // hydration이 모두 끝난 다음 한 번만 @PostLoad를 발화해, 사용자 callback에서 다른 필드를 같이 읽을 수 있게 한다.
        listenerInvoker.invokePostLoad(instance, metadata);
        return instance;
    }

    /**
     * Embedded leaf 값들을 host path 기준으로 hydrate한다. 어떤 nesting 레벨이든 모든 하위 leaf 값이 null이면
     * 그 레벨의 호스트를 null로 두고 더 깊은 레벨의 leaf는 무시한다. 그렇지 않은 path만 실제로 write가 일어나며,
     * 중간 호스트 인스턴스는 {@link PersistentProperty#write} 내부에서 lazy하게 생성된다.
     */
    private void hydrateEmbeddedValues(Object instance, List<EmbeddedValue> values) {
        if (values.isEmpty()) {
            return;
        }
        // path-prefix별로 모든 직간접 leaf 값이 null인지 미리 계산해 둔다. 키는 host path 자체이며,
        // 길이 0(empty)에 대한 항목은 만들지 않는다(top-level entity는 null로 둘 수 없으므로).
        LinkedHashMap<List<java.lang.reflect.Field>, boolean[]> allNullByPrefix = new LinkedHashMap<>();
        for (EmbeddedValue ev : values) {
            List<java.lang.reflect.Field> path = ev.property().embeddedHostPath();
            for (int depth = 1; depth <= path.size(); depth++) {
                List<java.lang.reflect.Field> prefix = path.subList(0, depth);
                boolean[] flag = allNullByPrefix.computeIfAbsent(prefix, ignored -> new boolean[]{true});
                if (ev.value() != null) {
                    flag[0] = false;
                }
            }
        }
        // 가장 짧은 prefix부터 검사해서 outer가 모두 null이면 그 레벨을 null로 두고 더 깊은 부분은 skip한다.
        // 호스트 인스턴스는 PersistentProperty#write가 필요 시 새로 만들어 두므로, all-null이 아닌 leaf만
        // write하면 된다. all-null인 outermost prefix를 만나면 그 prefix의 마지막 호스트 필드를 명시적으로
        // null로 설정해 둔다 — 사용자가 entity 생성자에서 미리 채워둔 빈 embeddable 인스턴스가 남지 않도록.
        for (EmbeddedValue ev : values) {
            List<java.lang.reflect.Field> path = ev.property().embeddedHostPath();
            // outer → inner 순으로 all-null prefix를 찾아 가장 외곽 레벨을 null로 만들고 leaf write는 skip.
            int allNullDepth = -1;
            for (int depth = 1; depth <= path.size(); depth++) {
                boolean[] flag = allNullByPrefix.get(path.subList(0, depth));
                if (flag != null && flag[0]) {
                    allNullDepth = depth;
                    break;
                }
            }
            if (allNullDepth > 0) {
                nullifyHostAtDepth(instance, path, allNullDepth);
                continue;
            }
            ev.property().write(instance, ev.value());
        }
    }

    /**
     * {@code path}의 outer → inner 순서를 따라 {@code depth - 1}번째까지 진입한 뒤(중간 호스트가 null이면 그대로 둔다),
     * 마지막 호스트 필드를 null로 만든다. 사용자가 명시적으로 생성자에서 빈 embeddable을 채워뒀더라도
     * 해당 hierarchy의 모든 leaf가 NULL이면 null로 정리해 round-trip을 안전하게 만든다.
     */
    private void nullifyHostAtDepth(Object instance, List<java.lang.reflect.Field> path, int depth) {
        try {
            Object current = instance;
            for (int i = 0; i < depth - 1; i++) {
                java.lang.reflect.Field hostField = path.get(i);
                hostField.setAccessible(true);
                current = hostField.get(current);
                if (current == null) {
                    return;
                }
            }
            java.lang.reflect.Field targetHost = path.get(depth - 1);
            targetHost.setAccessible(true);
            targetHost.set(current, null);
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException(
                    "Cannot null out embedded host field " + path.get(depth - 1).getName(), exception);
        }
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
            // converter property는 저장 타입(columnType)을, primitive는 boxed wrapper를 요청한다.
            // 자세한 사유는 mapRow의 동일 주석 참고.
            Object raw = row.get(property.columnName(), wrapPrimitive(property.columnType()));
            arguments[i] = property.toPropertyValue(raw);
        }
        try {
            return constructor.newInstance(arguments);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(
                    "Cannot instantiate projection " + constructor.getDeclaringClass().getName(), exception);
        }
    }

    @SuppressWarnings("unchecked")
    private <T> T instantiate(Class<T> entityType) {
        // no-arg 생성자를 type별로 1회만 lookup·setAccessible 해 캐시한다 — row마다 getDeclaredConstructor를
        // 반복하던 reflective lookup 비용을 제거한다(newInstance 할당 자체는 불가피).
        Constructor<T> constructor = (Constructor<T>) constructorCache.computeIfAbsent(entityType, type -> {
            try {
                Constructor<?> resolved = type.getDeclaredConstructor();
                resolved.setAccessible(true);
                return resolved;
            } catch (NoSuchMethodException exception) {
                throw new IllegalStateException(
                        "Entity type must expose a no-args constructor: " + type.getName(), exception);
            }
        });
        try {
            return constructor.newInstance();
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Cannot instantiate " + entityType.getName(), exception);
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
