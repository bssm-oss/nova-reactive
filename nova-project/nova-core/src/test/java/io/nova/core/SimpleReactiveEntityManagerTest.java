package io.nova.core;

import io.nova.metadata.DefaultNamingStrategy;
import io.nova.metadata.EntityMetadata;
import io.nova.metadata.EntityMetadataFactory;
import io.nova.query.QuerySpec;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.FlushModeType;
import jakarta.persistence.Id;
import jakarta.persistence.LockModeType;
import jakarta.persistence.Version;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.util.context.Context;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SimpleReactiveEntityManager}가 JPA EntityManager 기능을 {@link ReactiveEntityOperations} public API와
 * Reactor Context 세션 위임으로 등가 제공하는지 검증한다. 세션 의존 연산은 {@code io.nova.core} 패키지 접근으로
 * {@link PersistenceSession}을 Context에 직접 실어 확인한다.
 */
class SimpleReactiveEntityManagerTest {

    private RecordingOperations operations;
    private EntityMetadataFactory metadataFactory;
    private SimpleReactiveEntityManager manager;

    @BeforeEach
    void setUp() {
        operations = new RecordingOperations();
        metadataFactory = new EntityMetadataFactory(new DefaultNamingStrategy());
        manager = new SimpleReactiveEntityManager(operations, metadataFactory);
    }

    @Test
    void persistDelegatesToSave() {
        Widget widget = new Widget(1L, "a");
        StepVerifier.create(manager.persist(widget))
                .expectNext(widget)
                .verifyComplete();
        assertEquals(List.of(widget), operations.saved);
    }

    @Test
    void mergeDelegatesToSave() {
        Widget widget = new Widget(2L, "b");
        StepVerifier.create(manager.merge(widget))
                .expectNext(widget)
                .verifyComplete();
        assertEquals(List.of(widget), operations.saved);
    }

    @Test
    void removeDelegatesToDeleteAndCompletesEmpty() {
        Widget widget = new Widget(3L, "c");
        StepVerifier.create(manager.remove(widget))
                .verifyComplete();
        assertEquals(List.of(widget), operations.deleted);
    }

    @Test
    void findDelegatesToFindById() {
        Widget widget = new Widget(4L, "d");
        operations.findByIdResult = widget;
        StepVerifier.create(manager.find(Widget.class, 4L))
                .expectNext(widget)
                .verifyComplete();
        assertEquals(List.of(4L), operations.findByIdIds);
    }

    @Test
    void getReferenceErrorsWhenAbsent() {
        operations.findByIdResult = null; // empty Mono
        StepVerifier.create(manager.getReference(Widget.class, 99L))
                .expectError(EntityNotFoundException.class)
                .verify();
    }

    @Test
    void getReferenceReturnsWhenPresent() {
        Widget widget = new Widget(5L, "e");
        operations.findByIdResult = widget;
        StepVerifier.create(manager.getReference(Widget.class, 5L))
                .expectNext(widget)
                .verifyComplete();
    }

    @Test
    void flushDelegatesToOperationsFlush() {
        StepVerifier.create(manager.flush())
                .verifyComplete();
        assertEquals(1, operations.flushCount);
    }

    @Test
    void containsIsFalseWithoutSession() {
        StepVerifier.create(manager.contains(new Widget(6L, "f")))
                .expectNext(Boolean.FALSE)
                .verifyComplete();
    }

    @Test
    void clearAndDetachAreNoOpWithoutSession() {
        StepVerifier.create(manager.clear()).verifyComplete();
        StepVerifier.create(manager.detach(new Widget(7L, "g"))).verifyComplete();
    }

    @Test
    void containsIsTrueForManagedEntityInSession() {
        PersistenceSession session = new PersistenceSession();
        Widget widget = new Widget(8L, "h");
        session.registerOnLoad(metadataFor(Widget.class), widget);

        StepVerifier.create(withSession(manager.contains(widget), session))
                .expectNext(Boolean.TRUE)
                .verifyComplete();
    }

    @Test
    void detachRemovesEntityFromSession() {
        PersistenceSession session = new PersistenceSession();
        Widget widget = new Widget(9L, "i");
        session.registerOnLoad(metadataFor(Widget.class), widget);

        StepVerifier.create(withSession(manager.detach(widget), session))
                .verifyComplete();
        StepVerifier.create(withSession(manager.contains(widget), session))
                .expectNext(Boolean.FALSE)
                .verifyComplete();
    }

    @Test
    void clearEmptiesSessionIdentityMap() {
        PersistenceSession session = new PersistenceSession();
        Widget widget = new Widget(10L, "j");
        session.registerOnLoad(metadataFor(Widget.class), widget);

        StepVerifier.create(withSession(manager.clear(), session))
                .verifyComplete();
        assertTrue(session.isEmpty(), "clear는 세션 identity map을 비워야 한다");
    }

    @Test
    void removeDetachesManagedEntityBeforeDelete() {
        PersistenceSession session = new PersistenceSession();
        Widget widget = new Widget(11L, "k");
        session.registerOnLoad(metadataFor(Widget.class), widget);

        StepVerifier.create(withSession(manager.remove(widget), session))
                .verifyComplete();
        assertEquals(List.of(widget), operations.deleted);
        assertTrue(session.isEmpty(), "remove는 삭제 전에 세션에서 분리해야 한다");
    }

    @Test
    void refreshReloadsColumnStateInPlaceAndReRegisters() {
        PersistenceSession session = new PersistenceSession();
        Widget managed = new Widget(12L, "stale");
        session.registerOnLoad(metadataFor(Widget.class), managed);
        managed.setName("locally-changed"); // 세션이 tracking하는 로컬 변경

        Widget fresh = new Widget(12L, "db-value");
        operations.findByIdResult = fresh;

        StepVerifier.create(withSession(manager.refresh(managed), session))
                .assertNext(refreshed -> {
                    assertSame(managed, refreshed, "refresh는 동일 인스턴스를 in-place로 갱신해야 한다");
                    assertEquals("db-value", refreshed.getName(), "DB 컬럼 상태로 재적재돼야 한다");
                })
                .verifyComplete();
        // 재적재 후 다시 관리 상태로 편입되어야 한다.
        StepVerifier.create(withSession(manager.contains(managed), session))
                .expectNext(Boolean.TRUE)
                .verifyComplete();
    }

    @Test
    void refreshRejectsTransientEntity() {
        StepVerifier.create(manager.refresh(new Widget(null, "x")))
                .expectError(IllegalArgumentException.class)
                .verify();
    }

    // ---------------------------------------------------------------------------------------------
    // FlushMode 전이
    // ---------------------------------------------------------------------------------------------

    @Test
    void flushModeDefaultsToAuto() {
        assertEquals(FlushModeType.AUTO, manager.getFlushMode());
    }

    @Test
    void setFlushModeReturnsConfiguredManagerFunctionally() {
        ReactiveEntityManager committing = manager.setFlushMode(FlushModeType.COMMIT);
        assertEquals(FlushModeType.COMMIT, committing.getFlushMode());
        // functional: 원본은 그대로 AUTO여야 한다(공유 인스턴스 mutate 금지).
        assertEquals(FlushModeType.AUTO, manager.getFlushMode());
    }

    @Test
    void setFlushModeToSameValueReturnsSameInstance() {
        assertSame(manager, manager.setFlushMode(FlushModeType.AUTO));
    }

    @Test
    void setFlushModeBackAndForth() {
        ReactiveEntityManager committing = manager.setFlushMode(FlushModeType.COMMIT);
        ReactiveEntityManager backToAuto = committing.setFlushMode(FlushModeType.AUTO);
        assertEquals(FlushModeType.AUTO, backToAuto.getFlushMode());
        assertEquals(FlushModeType.COMMIT, committing.getFlushMode());
    }

    // ---------------------------------------------------------------------------------------------
    // 잠금(LockModeType) + find 오버로드
    // ---------------------------------------------------------------------------------------------

    @Test
    void findWithPessimisticWriteIssuesForUpdateSelect() {
        Widget widget = new Widget(4L, "d");
        operations.findAllResults = List.of(widget);

        StepVerifier.create(manager.find(Widget.class, 4L, LockModeType.PESSIMISTIC_WRITE))
                .expectNext(widget)
                .verifyComplete();

        assertEquals(1, operations.findAllSpecs.size());
        assertEquals(io.nova.query.LockMode.FOR_UPDATE, operations.findAllSpecs.get(0).lockMode());
    }

    @Test
    void findWithPessimisticReadIssuesForShareSelect() {
        Widget widget = new Widget(4L, "d");
        operations.findAllResults = List.of(widget);

        StepVerifier.create(manager.find(Widget.class, 4L, LockModeType.PESSIMISTIC_READ))
                .expectNext(widget)
                .verifyComplete();

        assertEquals(io.nova.query.LockMode.FOR_SHARE, operations.findAllSpecs.get(0).lockMode());
    }

    @Test
    void findWithNoneLockModeUsesPlainFindById() {
        Widget widget = new Widget(4L, "d");
        operations.findByIdResult = widget;

        StepVerifier.create(manager.find(Widget.class, 4L, LockModeType.NONE))
                .expectNext(widget)
                .verifyComplete();

        assertEquals(List.of(4L), operations.findByIdIds);
        assertTrue(operations.findAllSpecs.isEmpty(), "NONE은 잠긴 SELECT를 쓰지 않는다");
    }

    @Test
    void findWithOptimisticOnNonVersionedEntityFailsFast() {
        StepVerifier.create(manager.find(Widget.class, 4L, LockModeType.OPTIMISTIC))
                .expectError(IllegalArgumentException.class)
                .verify();
    }

    @Test
    void findWithVersionedEntityAndOptimisticUsesPlainFindById() {
        VersionedWidget widget = new VersionedWidget(1L, 0L);
        operations.findByIdResult = widget;

        StepVerifier.create(manager.find(VersionedWidget.class, 1L, LockModeType.OPTIMISTIC))
                .expectNext(widget)
                .verifyComplete();
        assertEquals(List.of(1L), operations.findByIdIds);
    }

    @Test
    void findWithForceIncrementBumpsVersionViaUpdate() {
        VersionedWidget widget = new VersionedWidget(1L, 0L);
        operations.findByIdResult = widget;

        StepVerifier.create(manager.find(VersionedWidget.class, 1L, LockModeType.OPTIMISTIC_FORCE_INCREMENT))
                .expectNext(widget)
                .verifyComplete();
        assertEquals(List.of(widget), operations.updated);
        assertEquals(List.of("version"), operations.updateFields.get(0));
    }

    @Test
    void findWithPropertiesMapDelegatesToFindById() {
        Widget widget = new Widget(4L, "d");
        operations.findByIdResult = widget;

        StepVerifier.create(manager.find(Widget.class, 4L, java.util.Map.of("hint", "ignored")))
                .expectNext(widget)
                .verifyComplete();
        assertEquals(List.of(4L), operations.findByIdIds);
    }

    @Test
    void lockOptimisticVerifiesVersionAndSucceedsWhenMatching() {
        VersionedWidget widget = new VersionedWidget(1L, 3L);
        operations.existsResult = true;

        StepVerifier.create(manager.lock(widget, LockModeType.OPTIMISTIC))
                .verifyComplete();
    }

    @Test
    void lockOptimisticFailsWhenVersionMismatch() {
        VersionedWidget widget = new VersionedWidget(1L, 3L);
        operations.existsResult = false; // DB row에 그 버전이 더 이상 없음

        StepVerifier.create(manager.lock(widget, LockModeType.OPTIMISTIC))
                .expectError(io.nova.exception.OptimisticLockingFailureException.class)
                .verify();
    }

    @Test
    void lockOptimisticOnNonVersionedEntityFailsFast() {
        StepVerifier.create(manager.lock(new Widget(1L, "a"), LockModeType.OPTIMISTIC))
                .expectError(IllegalArgumentException.class)
                .verify();
    }

    @Test
    void lockPessimisticWriteReselectsWithForUpdate() {
        VersionedWidget widget = new VersionedWidget(1L, 3L);
        operations.findAllResults = List.of(widget);

        StepVerifier.create(manager.lock(widget, LockModeType.PESSIMISTIC_WRITE))
                .verifyComplete();
        assertEquals(io.nova.query.LockMode.FOR_UPDATE, operations.findAllSpecs.get(0).lockMode());
    }

    @Test
    void getLockModeReturnsOptimisticForManagedVersionedEntity() {
        PersistenceSession session = new PersistenceSession();
        VersionedWidget widget = new VersionedWidget(1L, 0L);
        session.registerOnLoad(metadataFactory.getEntityMetadata(VersionedWidget.class), widget);

        StepVerifier.create(withSession(manager.getLockMode(widget), session))
                .expectNext(LockModeType.OPTIMISTIC)
                .verifyComplete();
    }

    @Test
    void getLockModeReturnsNoneWithoutSession() {
        StepVerifier.create(manager.getLockMode(new VersionedWidget(1L, 0L)))
                .expectNext(LockModeType.NONE)
                .verifyComplete();
    }

    private EntityMetadata<Widget> metadataFor(Class<Widget> type) {
        return metadataFactory.getEntityMetadata(type);
    }

    private <T> Mono<T> withSession(Mono<T> mono, PersistenceSession session) {
        return mono.contextWrite(Context.of(SimpleReactiveEntityOperations.SESSION_KEY, session));
    }

    @Entity
    static class Widget {
        @Id
        private Long id;
        private String name;

        Widget() {
        }

        Widget(Long id, String name) {
            this.id = id;
            this.name = name;
        }

        Long getId() {
            return id;
        }

        String getName() {
            return name;
        }

        void setName(String name) {
            this.name = name;
        }
    }

    @Entity
    static class VersionedWidget {
        @Id
        private Long id;
        @Version
        private Long version;

        VersionedWidget() {
        }

        VersionedWidget(Long id, Long version) {
            this.id = id;
            this.version = version;
        }

        Long getId() {
            return id;
        }

        Long getVersion() {
            return version;
        }
    }

    /**
     * 호출을 기록하고 구성 가능한 결과를 돌려주는 최소 {@link ReactiveEntityOperations} 테스트 더블.
     * default 메서드({@code flush} 포함)는 그대로 두되, {@code flush}는 카운트를 위해 override한다.
     */
    private static final class RecordingOperations implements ReactiveEntityOperations {
        private final List<Object> saved = new ArrayList<>();
        private final List<Object> deleted = new ArrayList<>();
        private final List<Object> findByIdIds = new ArrayList<>();
        private final List<QuerySpec> findAllSpecs = new ArrayList<>();
        private final List<Object> updated = new ArrayList<>();
        private final List<List<String>> updateFields = new ArrayList<>();
        private Object findByIdResult;
        private List<Object> findAllResults = new ArrayList<>();
        private boolean existsResult;
        private int flushCount;

        @Override
        @SuppressWarnings("unchecked")
        public <T> Mono<T> save(T entity) {
            saved.add(entity);
            return Mono.just(entity);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T, ID> Mono<T> findById(Class<T> entityType, ID id) {
            findByIdIds.add(id);
            return findByIdResult == null ? Mono.empty() : Mono.just((T) findByIdResult);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> Flux<T> findAll(Class<T> entityType, QuerySpec querySpec) {
            findAllSpecs.add(querySpec);
            return (Flux<T>) Flux.fromIterable(findAllResults);
        }

        @Override
        public <T> Mono<T> update(T entity, Iterable<String> fields) {
            updated.add(entity);
            List<String> collected = new ArrayList<>();
            fields.forEach(collected::add);
            updateFields.add(collected);
            return Mono.just(entity);
        }

        @Override
        public <T> Mono<Long> delete(T entity) {
            deleted.add(entity);
            return Mono.just(1L);
        }

        @Override
        public <T, ID> Mono<Long> deleteById(Class<T> entityType, ID id) {
            return Mono.just(0L);
        }

        @Override
        public <T> Mono<Long> count(Class<T> entityType, QuerySpec querySpec) {
            return Mono.just(0L);
        }

        @Override
        public <T> Mono<Boolean> exists(Class<T> entityType, QuerySpec querySpec) {
            return Mono.just(existsResult);
        }

        @Override
        public Mono<Void> flush() {
            flushCount++;
            return Mono.empty();
        }

        @Override
        public <R> Mono<R> inTransaction(Function<ReactiveEntityOperations, Mono<R>> callback) {
            return callback.apply(this);
        }
    }
}
