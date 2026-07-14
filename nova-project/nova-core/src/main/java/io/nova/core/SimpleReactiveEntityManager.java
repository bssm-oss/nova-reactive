package io.nova.core;

import io.nova.metadata.EntityMetadata;
import io.nova.metadata.EntityMetadataFactory;
import io.nova.metadata.PersistentProperty;
import io.nova.query.Criteria;
import io.nova.query.LockMode;
import io.nova.query.LockModeTranslator;
import io.nova.query.LogicalOperator;
import io.nova.query.Predicate;
import io.nova.query.QuerySpec;
import io.nova.query.CompoundPredicate;
import io.nova.query.storedprocedure.NamedStoredProcedureRegistry;
import io.nova.query.storedprocedure.ReactiveStoredProcedureQuery;
import io.nova.query.storedprocedure.StoredProcedureParameterDefinition;
import io.nova.query.storedprocedure.StoredProcedureRowMappers;
import io.nova.exception.OptimisticLockingFailureException;
import io.nova.sql.Dialect;
import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.FlushModeType;
import jakarta.persistence.LockModeType;
import reactor.core.publisher.Mono;
import reactor.util.context.ContextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * 기존 {@link ReactiveEntityOperations}의 <b>public</b> API와, Reactor {@code Context}에 바인딩된
 * {@link PersistenceSession}에만 위임하는 기본 {@link ReactiveEntityManager} 구현이다.
 *
 * <p>세션 조회는 {@link SimpleReactiveEntityOperations#SESSION_KEY}(nova-core 내부 키)를 통해 이루어지며,
 * 이 클래스는 세션 flush/컬렉션/스냅샷의 <em>내부 알고리즘을 재구현하지 않는다</em> — flush는
 * {@link ReactiveEntityOperations#flush()}로, identity map 조작은 {@link PersistenceSession}의 기존
 * package-private 메서드(clear/detach/isManaged/registerOnLoad)로 위임한다.
 */
public final class SimpleReactiveEntityManager implements ReactiveEntityManager {

    private final ReactiveEntityOperations operations;
    private final EntityMetadataFactory metadataFactory;
    /**
     * 이 매니저 인스턴스의 {@link FlushModeType}. 공유 매니저의 가변 상태를 피하려고 immutable하게 두며,
     * {@link #setFlushMode(FlushModeType)}는 mutate 대신 이 값을 바꾼 새 인스턴스를 돌려준다(functional).
     * {@link #inTransaction}/{@link #inReadSession}가 이 값을 세션 컨텍스트({@link SimpleReactiveEntityOperations#FLUSH_MODE_KEY})에
     * 심어 operations의 쿼리 전 auto-flush 동작을 제어한다.
     */
    private final FlushModeType flushMode;
    /**
     * 저장 프로시저 CALL 렌더링에 필요한 dialect. 이 collaborator 없이 만들어진 매니저(기존 2-arg 생성자)는
     * SP 관련 메서드가 fail-fast 한다 — {@link Dialect}를 주입한 생성자로 만들어야 SP를 사용할 수 있다.
     */
    private final Dialect dialect;

    public SimpleReactiveEntityManager(
            ReactiveEntityOperations operations, EntityMetadataFactory metadataFactory) {
        this(operations, metadataFactory, null, FlushModeType.AUTO);
    }

    /**
     * 저장 프로시저(W7)를 사용하려면 {@link Dialect}를 함께 주입한다 — CALL 문 렌더링에 필요하다.
     */
    public SimpleReactiveEntityManager(
            ReactiveEntityOperations operations, EntityMetadataFactory metadataFactory, Dialect dialect) {
        this(operations, metadataFactory, dialect, FlushModeType.AUTO);
    }

    private SimpleReactiveEntityManager(
            ReactiveEntityOperations operations, EntityMetadataFactory metadataFactory,
            Dialect dialect, FlushModeType flushMode) {
        this.operations = Objects.requireNonNull(operations, "operations must not be null");
        this.metadataFactory = Objects.requireNonNull(metadataFactory, "metadataFactory must not be null");
        this.dialect = dialect;
        this.flushMode = Objects.requireNonNull(flushMode, "flushMode must not be null");
    }

    @Override
    public <T> Mono<T> persist(T entity) {
        Objects.requireNonNull(entity, "entity must not be null");
        return operations.save(entity);
    }

    @Override
    public <T> Mono<T> merge(T entity) {
        Objects.requireNonNull(entity, "entity must not be null");
        return operations.save(entity);
    }

    @Override
    public Mono<Void> remove(Object entity) {
        Objects.requireNonNull(entity, "entity must not be null");
        return Mono.deferContextual(ctx -> {
            // 세션이 있으면 먼저 분리해 이 엔티티의 미flush 변경이 뒤늦게 UPDATE로 나가지 않게 한 뒤 DELETE한다.
            currentSession(ctx).ifPresent(session -> session.detach(metadataFor(entity), entity));
            return operations.delete(entity).then();
        });
    }

    @Override
    public <T> Mono<T> find(Class<T> entityType, Object id) {
        Objects.requireNonNull(entityType, "entityType must not be null");
        Objects.requireNonNull(id, "id must not be null");
        return operations.findById(entityType, id);
    }

    @Override
    public <T> Mono<T> getReference(Class<T> entityType, Object id) {
        Objects.requireNonNull(entityType, "entityType must not be null");
        Objects.requireNonNull(id, "id must not be null");
        return operations.findById(entityType, id)
                .switchIfEmpty(Mono.error(() -> new EntityNotFoundException(
                        "Unable to find " + entityType.getName() + " with id " + id)));
    }

    @Override
    public Mono<Void> flush() {
        return operations.flush();
    }

    @Override
    public Mono<Void> clear() {
        return Mono.deferContextual(ctx -> {
            currentSession(ctx).ifPresent(PersistenceSession::clear);
            return Mono.<Void>empty();
        });
    }

    @Override
    public Mono<Void> detach(Object entity) {
        Objects.requireNonNull(entity, "entity must not be null");
        return Mono.deferContextual(ctx -> {
            currentSession(ctx).ifPresent(session -> session.detach(metadataFor(entity), entity));
            return Mono.<Void>empty();
        });
    }

    @Override
    public Mono<Boolean> contains(Object entity) {
        Objects.requireNonNull(entity, "entity must not be null");
        return Mono.deferContextual(ctx -> Mono.just(currentSession(ctx)
                .map(session -> session.isManaged(metadataFor(entity), entity))
                .orElse(Boolean.FALSE)));
    }

    @Override
    public <T> Mono<T> refresh(T entity) {
        Objects.requireNonNull(entity, "entity must not be null");
        return Mono.deferContextual(ctx -> {
            @SuppressWarnings("unchecked")
            EntityMetadata<T> metadata = (EntityMetadata<T>) metadataFactory.getEntityMetadata(entity.getClass());
            Object id = metadata.readIdValue(entity);
            if (id == null) {
                return Mono.error(new IllegalArgumentException(
                        "Cannot refresh a transient " + entity.getClass().getName()
                                + " with a null identifier; persist it first"));
            }
            Optional<PersistenceSession> session = currentSession(ctx);
            // 보류 변경 폐기: 재조회 전에 세션에서 분리해 auto-flush가 이 엔티티의 미저장 변경을 쓰지 않게 한다.
            session.ifPresent(active -> active.detach(metadata, entity));
            Class<T> entityType = metadata.entityType();
            // SESSION_KEY를 제거한 컨텍스트로 조회 = 세션-less raw read(auto-flush/identity 편입 없음). 커넥션 키는
            // 남으므로 동일 트랜잭션 커넥션에서 현재 DB 상태를 읽는다 — 방금 폐기한 미flush 변경은 반영되지 않는다.
            return operations.findById(entityType, id)
                    .contextWrite(context -> context.delete(SimpleReactiveEntityOperations.SESSION_KEY))
                    .switchIfEmpty(Mono.error(() -> new EntityNotFoundException(
                            "Unable to refresh " + entityType.getName() + " with id " + id
                                    + "; the row no longer exists")))
                    .map(fresh -> {
                        copyColumnState(metadata, fresh, entity);
                        // 재적재한 인스턴스를 clean snapshot으로 다시 관리 상태에 편입한다(분리 상태라 key가 비어 있음).
                        session.ifPresent(active -> active.registerOnLoad(metadata, entity));
                        return entity;
                    });
        });
    }

    @Override
    public <R> Mono<R> inTransaction(Function<ReactiveEntityManager, Mono<R>> work) {
        Objects.requireNonNull(work, "work must not be null");
        // 이 매니저의 FlushMode를 세션 컨텍스트에 심어, 콜백 안의 쿼리(operations.findById/findAll)가
        // COMMIT일 때 쿼리 전 auto-flush를 억제하도록 한다. 콜백에는 this를 전달해 동일 세션을 공유한다.
        return operations.inTransaction(ignored -> work.apply(this))
                .contextWrite(ctx -> ctx.put(SimpleReactiveEntityOperations.FLUSH_MODE_KEY, flushMode));
    }

    @Override
    public <R> Mono<R> inReadSession(Function<ReactiveEntityManager, Mono<R>> work) {
        Objects.requireNonNull(work, "work must not be null");
        return operations.inReadSession(ignored -> work.apply(this))
                .contextWrite(ctx -> ctx.put(SimpleReactiveEntityOperations.FLUSH_MODE_KEY, flushMode));
    }

    // ---------------------------------------------------------------------------------------------
    // FlushMode (JPA setFlushMode/getFlushMode)
    // ---------------------------------------------------------------------------------------------

    @Override
    public ReactiveEntityManager setFlushMode(FlushModeType flushMode) {
        Objects.requireNonNull(flushMode, "flushMode must not be null");
        if (flushMode == this.flushMode) {
            return this;
        }
        return new SimpleReactiveEntityManager(operations, metadataFactory, dialect, flushMode);
    }

    @Override
    public FlushModeType getFlushMode() {
        return flushMode;
    }

    // ---------------------------------------------------------------------------------------------
    // 저장 프로시저(@StoredProcedureQuery / @NamedStoredProcedureQuery) — W7
    // ---------------------------------------------------------------------------------------------

    @Override
    public ReactiveStoredProcedureQuery<?> createStoredProcedureQuery(
            String procedureName, List<StoredProcedureParameterDefinition> parameters) {
        return newStoredProcedureQuery(procedureName, parameters, null);
    }

    @Override
    public <T> ReactiveStoredProcedureQuery<T> createStoredProcedureQuery(
            String procedureName, List<StoredProcedureParameterDefinition> parameters, Class<T> resultClass) {
        Objects.requireNonNull(resultClass, "resultClass must not be null");
        return newStoredProcedureQuery(
                procedureName, parameters, StoredProcedureRowMappers.entity(metadataFactory, resultClass));
    }

    @Override
    public <T> ReactiveStoredProcedureQuery<T> createStoredProcedureQuery(
            String procedureName, List<StoredProcedureParameterDefinition> parameters,
            Function<RowAccessor, T> mapper) {
        Objects.requireNonNull(mapper, "mapper must not be null");
        return newStoredProcedureQuery(procedureName, parameters, mapper);
    }

    @Override
    public ReactiveStoredProcedureQuery<?> createNamedStoredProcedureQuery(
            String name, NamedStoredProcedureRegistry registry) {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(registry, "registry must not be null");
        return registry.createNamedStoredProcedureQuery(name);
    }

    private <T> ReactiveStoredProcedureQuery<T> newStoredProcedureQuery(
            String procedureName, List<StoredProcedureParameterDefinition> parameters,
            Function<RowAccessor, T> mapper) {
        Objects.requireNonNull(procedureName, "procedureName must not be null");
        Objects.requireNonNull(parameters, "parameters must not be null");
        if (dialect == null) {
            throw new IllegalStateException(
                    "This ReactiveEntityManager was constructed without a Dialect; stored procedure queries"
                            + " require a Dialect to render the CALL statement. Build it with"
                            + " new SimpleReactiveEntityManager(operations, metadataFactory, dialect).");
        }
        return new ReactiveStoredProcedureQuery<>(procedureName, parameters, mapper, operations, dialect);
    }

    // ---------------------------------------------------------------------------------------------
    // JPA 잠금(LockModeType) + find 오버로드
    // ---------------------------------------------------------------------------------------------

    @Override
    public <T> Mono<T> find(Class<T> entityType, Object id, LockModeType lockMode) {
        Objects.requireNonNull(entityType, "entityType must not be null");
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(lockMode, "lockMode must not be null");
        return Mono.defer(() -> {
            EntityMetadata<T> metadata = metadataFactory.getEntityMetadata(entityType);
            LockModeTranslator.ResolvedLock resolved = LockModeTranslator.resolve(lockMode);
            if (resolved.versionCheck() || resolved.forceIncrement()) {
                if (metadata.versionProperty().isEmpty()) {
                    return Mono.error(new IllegalArgumentException(
                            "Lock mode " + lockMode + " requires a @Version property on " + entityType.getName()));
                }
            }
            // PESSIMISTIC_*: FOR UPDATE/SHARE로 잠긴 SELECT. OPTIMISTIC/NONE: 일반 findById(버전 검증은 이미
            // 로드한 값이 곧 현재 값이므로 별도 SQL 없이 성립; 강제 증분만 추가 UPDATE 발행).
            Mono<T> found = resolved.lockMode() == LockMode.NONE
                    ? operations.findById(entityType, id)
                    : operations.findAll(entityType, idQuerySpec(metadata, id).lockMode(resolved.lockMode())).next();
            if (resolved.forceIncrement()) {
                PersistentProperty versionProperty = metadata.versionProperty().orElseThrow();
                return found.flatMap(entity -> operations.update(entity, List.of(versionProperty.propertyName())));
            }
            return found;
        });
    }

    @Override
    public Mono<Void> lock(Object entity, LockModeType lockMode) {
        Objects.requireNonNull(entity, "entity must not be null");
        Objects.requireNonNull(lockMode, "lockMode must not be null");
        return Mono.defer(() -> {
            EntityMetadata<?> metadata = metadataFor(entity);
            LockModeTranslator.ResolvedLock resolved = LockModeTranslator.resolve(lockMode);
            if ((resolved.versionCheck() || resolved.forceIncrement()) && metadata.versionProperty().isEmpty()) {
                return Mono.error(new IllegalArgumentException(
                        "Lock mode " + lockMode + " requires a @Version property on "
                                + metadata.entityType().getName()));
            }
            Mono<Void> chain = Mono.empty();
            if (resolved.lockMode() != LockMode.NONE) {
                // PESSIMISTIC_*: 해당 행을 FOR UPDATE/SHARE로 재조회해 DB 잠금을 획득한다.
                chain = chain.then(lockedReselect(metadata, entity, resolved.lockMode()));
            }
            if (resolved.forceIncrement()) {
                // *_FORCE_INCREMENT: 버전을 강제 증분하는 UPDATE 발행(낙관락 검증 포함).
                PersistentProperty versionProperty = metadata.versionProperty().orElseThrow();
                chain = chain.then(operations.update(entity, List.of(versionProperty.propertyName())).then());
            } else if (resolved.versionCheck()) {
                // OPTIMISTIC/READ: 현재 버전이 DB와 일치하는지 검증한다.
                chain = chain.then(verifyVersion(metadata, entity));
            }
            return chain;
        });
    }

    @Override
    public Mono<LockModeType> getLockMode(Object entity) {
        Objects.requireNonNull(entity, "entity must not be null");
        return Mono.deferContextual(ctx -> {
            EntityMetadata<?> metadata = metadataFor(entity);
            boolean managed = currentSession(ctx)
                    .map(session -> session.isManaged(metadata, entity))
                    .orElse(Boolean.FALSE);
            // Nova는 per-entity 잠금 상태를 추적하지 않는다. 관리 중이고 @Version이 있으면 OPTIMISTIC, 그 외 NONE.
            return Mono.just(managed && metadata.versionProperty().isPresent()
                    ? LockModeType.OPTIMISTIC
                    : LockModeType.NONE);
        });
    }

    @Override
    public <T> Mono<T> refresh(T entity, LockModeType lockMode) {
        Objects.requireNonNull(entity, "entity must not be null");
        Objects.requireNonNull(lockMode, "lockMode must not be null");
        return refresh(entity).flatMap(refreshed -> lock(refreshed, lockMode).thenReturn(refreshed));
    }

    /**
     * id로 {@code id = ?} (단일 {@code @Id}) 또는 {@code c1 = ? and c2 = ?} (복합키) 술어를 가진
     * {@link QuerySpec}을 만든다. 잠금 재조회/버전 검증의 WHERE 절 구성에 쓰인다.
     */
    private QuerySpec idQuerySpec(EntityMetadata<?> metadata, Object id) {
        return QuerySpec.empty().where(idPredicate(metadata, id));
    }

    /**
     * id 객체의 모든 {@code @Id} 컴포넌트를 {@code and}로 결합한 술어를 만든다. 단일 {@code @Id}는 하나의
     * {@code eq}, {@code @EmbeddedId}/{@code @IdClass} 복합키는 컴포넌트별 {@code eq}를 AND로 묶는다.
     * 각 컴포넌트 값은 {@link EntityMetadata#idColumnValue(PersistentProperty, Object)}로 id 객체에서 꺼낸다
     * (단일 키에선 id 객체 자체가 값).
     */
    private static Predicate idPredicate(EntityMetadata<?> metadata, Object id) {
        List<PersistentProperty> idProperties = metadata.idProperties();
        List<Predicate> components = new ArrayList<>(idProperties.size());
        for (PersistentProperty idProperty : idProperties) {
            components.add(Criteria.eq(idProperty.propertyName(), metadata.idColumnValue(idProperty, id)));
        }
        return components.size() == 1
                ? components.get(0)
                : new CompoundPredicate(LogicalOperator.AND, components);
    }

    /**
     * 엔티티 행을 {@code FOR UPDATE}/{@code FOR SHARE}로 재조회해 DB 잠금을 획득한다. 행이 사라졌으면
     * {@link OptimisticLockingFailureException}으로 실패한다.
     */
    private Mono<Void> lockedReselect(EntityMetadata<?> metadata, Object entity, LockMode lockMode) {
        Object idValue = metadata.readIdValue(entity);
        QuerySpec spec = idQuerySpec(metadata, idValue).lockMode(lockMode);
        return operations.findAll(metadata.entityType(), spec).next()
                .switchIfEmpty(Mono.error(() -> new OptimisticLockingFailureException(
                        "Cannot acquire lock; row no longer exists for "
                                + metadata.entityType().getName() + " id=" + idValue)))
                .then();
    }

    /**
     * OPTIMISTIC 잠금 검증: DB의 현재 행 버전이 엔티티가 들고 있는 버전과 일치하는지 존재 쿼리로 확인한다.
     * 불일치(다른 트랜잭션이 이미 갱신)면 {@link OptimisticLockingFailureException}.
     */
    private Mono<Void> verifyVersion(EntityMetadata<?> metadata, Object entity) {
        PersistentProperty versionProperty = metadata.versionProperty().orElseThrow();
        Object idValue = metadata.readIdValue(entity);
        Object currentVersion = versionProperty.read(entity);
        Predicate predicate = new CompoundPredicate(LogicalOperator.AND, List.of(
                idPredicate(metadata, idValue),
                Criteria.eq(versionProperty.propertyName(), currentVersion)));
        return operations.exists(metadata.entityType(), QuerySpec.empty().where(predicate))
                .flatMap(exists -> exists
                        ? Mono.empty()
                        : Mono.error(new OptimisticLockingFailureException(
                                "Optimistic lock verification failed for " + metadata.entityType().getName()
                                        + " id=" + idValue + " version=" + currentVersion)));
    }

    /**
     * fresh 인스턴스의 컬럼 매핑 property 값들을 target 인스턴스에 그대로 복사한다(도메인 값 복사이므로 컬럼
     * 컨버전은 개입하지 않는다). 연관 property는 컬럼 매핑이 아니므로 복사 대상이 아니다.
     */
    private static <T> void copyColumnState(EntityMetadata<T> metadata, Object source, Object target) {
        for (PersistentProperty property : metadata.columnMappedProperties()) {
            property.write(target, property.read(source));
        }
    }

    private EntityMetadata<?> metadataFor(Object entity) {
        return metadataFactory.getEntityMetadata(entity.getClass());
    }

    private static Optional<PersistenceSession> currentSession(ContextView ctx) {
        return ctx.hasKey(SimpleReactiveEntityOperations.SESSION_KEY)
                ? Optional.of(ctx.get(SimpleReactiveEntityOperations.SESSION_KEY))
                : Optional.empty();
    }
}
