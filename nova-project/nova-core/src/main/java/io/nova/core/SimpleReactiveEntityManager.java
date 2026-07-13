package io.nova.core;

import io.nova.metadata.EntityMetadata;
import io.nova.metadata.EntityMetadataFactory;
import io.nova.metadata.PersistentProperty;
import jakarta.persistence.EntityNotFoundException;
import reactor.core.publisher.Mono;
import reactor.util.context.ContextView;

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

    public SimpleReactiveEntityManager(
            ReactiveEntityOperations operations, EntityMetadataFactory metadataFactory) {
        this.operations = Objects.requireNonNull(operations, "operations must not be null");
        this.metadataFactory = Objects.requireNonNull(metadataFactory, "metadataFactory must not be null");
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
        return operations.inTransaction(ignored -> work.apply(this));
    }

    @Override
    public <R> Mono<R> inReadSession(Function<ReactiveEntityManager, Mono<R>> work) {
        Objects.requireNonNull(work, "work must not be null");
        return operations.inReadSession(ignored -> work.apply(this));
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
