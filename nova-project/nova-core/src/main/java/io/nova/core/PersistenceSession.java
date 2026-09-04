package io.nova.core;

import io.nova.metadata.EntityMetadata;
import io.nova.metadata.PersistentProperty;
import jakarta.persistence.LockModeType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 트랜잭션 1개에 묶이는 영속성 세션(unit of work)의 in-memory 상태다. identity map(같은 PK = 같은 인스턴스)과
 * 로드 시점 컬럼 스냅샷을 보관하며, flush 시점에 스냅샷과 현재 상태를 비교해 변경된 컬럼만 UPDATE하도록
 * dirty 정보를 제공한다.
 * <p>
 * 이 클래스는 순수 자료구조다 — {@link reactor.core.publisher.Mono}/{@link reactor.core.publisher.Flux},
 * SQL, I/O를 일절 다루지 않는다. 실제 SELECT/UPDATE 발행과 reactive 합성은 {@link SimpleReactiveEntityOperations}가
 * 이 세션을 구동하며 수행한다. 세션은 트랜잭션 수명과 같고 Reactor {@code Context}에 실려 전파된다(ThreadLocal 금지).
 */
final class PersistenceSession {

    /**
     * identity map의 키. 로드 인스턴스의 <em>concrete</em> 타입(SINGLE_TABLE 서브타입 충돌 방지)과 id 컬럼
     * 값들의 구조 리스트로 구성한다. 복합키({@code @EmbeddedId}/{@code @IdClass})에서 사용자 {@code equals}에
     * 의존하지 않도록, holder 객체 대신 컬럼 값 리스트로 동등성을 판정한다.
     */
    record EntityKey(Class<?> entityType, List<Object> idValues) {
    }

    /**
     * 세션이 관리하는 엔티티 1건. 로드/persist 시점의 컬럼 스냅샷을 보관하고, 현재 상태와 비교해 변경된
     * property 이름을 제공한다.
     */
    /**
     * 컬렉션 스냅샷 자리에 두는 센티넬 — 영속 baseline을 알 수 없는 경우(detached 엔티티를 직접 save)다. flush는
     * 이 표시를 만나면 diff 대신 full-replace로 동기화해 현행 save(detached) 의미(전체 재작성)를 보존한다.
     */
    static final Object FORCE_FULL = new Object();

    static final class ManagedEntry {
        enum State {
            MANAGED,
            REMOVED
        }

        private final Object entity;
        private final EntityMetadata<?> metadata;
        private final boolean loaded;
        private Map<String, Object> snapshot;
        private State state = State.MANAGED;
        private LockModeType lockMode = LockModeType.NONE;
        // propertyName -> 컬렉션의 영속 baseline 정규 표현(ops가 만들어 넣는다: multiset Map / ordered List /
        // Map / FORCE_FULL). 키가 없으면 "아직 baseline 미캡처"(로드 hydration 전 등)다.
        private final Map<String, Object> collectionSnapshots = new LinkedHashMap<>();

        private ManagedEntry(Object entity, EntityMetadata<?> metadata, Map<String, Object> snapshot, boolean loaded) {
            this.entity = entity;
            this.metadata = metadata;
            this.snapshot = snapshot;
            this.loaded = loaded;
        }

        Object entity() {
            return entity;
        }

        EntityMetadata<?> metadata() {
            return metadata;
        }

        boolean isRemoved() {
            return state == State.REMOVED;
        }

        boolean loaded() {
            return loaded;
        }

        void markRemoved() {
            state = State.REMOVED;
            lockMode = LockModeType.NONE;
        }

        LockModeType lockMode() {
            return lockMode;
        }

        void setLockMode(LockModeType lockMode) {
            this.lockMode = lockMode;
        }

        Object snapshotColumnValue(String columnName) {
            return snapshot.get(columnName);
        }

        Object snapshotPropertyValue(PersistentProperty property) {
            return snapshot.get(property.columnName());
        }

        /**
         * 스냅샷 대비 변경된 updatable 컬럼의 property 이름을 declaration 순서로 반환한다. 저장형
         * ({@link PersistentProperty#toColumnValue}) 값으로 비교하므로 converter 컬럼도 저장 표현 기준으로
         * 정확히 diff된다. 변경 없으면 빈 리스트.
         */
        List<String> dirtyPropertyNames() {
            List<String> changed = new ArrayList<>();
            for (PersistentProperty property : metadata.updatableProperties()) {
                Object current = snapshotValue(property, entity);
                Object before = snapshot.get(property.columnName());
                if (!Objects.equals(before, current)) {
                    changed.add(property.propertyName());
                }
            }
            return changed;
        }

        /**
         * 현재 엔티티 상태로 스냅샷을 다시 찍는다. flush UPDATE 성공 후 호출해 동일 tx 내 두 번째 flush가
         * 재변경 없으면 no-op이 되도록 한다.
         */
        void refreshSnapshot() {
            this.snapshot = buildSnapshot(metadata, entity);
        }

        /**
         * 직접 부분 UPDATE가 성공한 뒤 실제로 기록된 컬럼만 clean baseline으로 맞춘다. 나머지 보류 변경은
         * 유지해야 하므로 {@link #refreshSnapshot()}처럼 엔티티 전체를 다시 캡처해서는 안 된다.
         */
        void refreshSnapshotFields(Iterable<PersistentProperty> properties) {
            for (PersistentProperty property : properties) {
                snapshot.put(property.columnName(), snapshotValue(property, entity));
            }
        }

        void refreshSnapshotFields(Map<String, Object> storageValues) {
            snapshot.putAll(storageValues);
        }

        /**
         * 컬렉션 property의 영속 baseline 정규 표현을 반환한다. 아직 캡처된 적이 없으면 {@code null}이다
         * ({@link #hasCollectionSnapshot}로 구분). flush가 이 값과 현재 컬렉션 표현을 비교해 변경 여부/diff를 정한다.
         */
        Object collectionSnapshot(String propertyName) {
            return collectionSnapshots.get(propertyName);
        }

        boolean hasCollectionSnapshot(String propertyName) {
            return collectionSnapshots.containsKey(propertyName);
        }

        /**
         * 컬렉션 baseline 표현을 설정한다 — 로드 후 캡처, 신규 INSERT 후 빈 baseline, detached save의 {@link #FORCE_FULL},
         * flush 성공 후 갱신에 모두 쓰인다. 표현 자체(multiset/list/map)는 ops 레이어가 만든다.
         */
        void putCollectionSnapshot(String propertyName, Object representation) {
            collectionSnapshots.put(propertyName, representation);
        }
    }

    private final Map<EntityKey, ManagedEntry> identityMap = new LinkedHashMap<>();

    /**
     * row 디코딩으로 갓 만들어진 엔티티를 세션에 편입한다. 같은 키가 이미 관리 중이면 기존 인스턴스를
     * 반환하고(identity 보장), 새로 만들어진 인스턴스는 버린다. id를 식별할 수 없으면(예: null id) 관리하지
     * 않고 입력 인스턴스를 그대로 반환한다.
     */
    @SuppressWarnings("unchecked")
    <T> T registerOnLoad(EntityMetadata<T> metadata, T entity) {
        EntityKey key = keyFor(metadata, entity);
        if (key == null) {
            return entity;
        }
        ManagedEntry existing = identityMap.get(key);
        if (existing != null) {
            return (T) existing.entity();
        }
        identityMap.put(key, new ManagedEntry(entity, metadata, buildSnapshot(metadata, entity), true));
        return entity;
    }

    /**
     * INSERT 성공 후(또는 세션 내 첫 save 시) 엔티티를 관리 대상으로 등록한다. id가 채워진 상태여야 하며,
     * 현재 상태로 baseline 스냅샷을 찍는다. 이미 관리 중인 키면 스냅샷만 갱신한다.
     */
    <T> void registerOnPersist(EntityMetadata<T> metadata, T entity) {
        EntityKey key = keyFor(metadata, entity);
        if (key == null) {
            return;
        }
        ManagedEntry existing = identityMap.get(key);
        if (existing != null) {
            if (existing.isRemoved()) {
                throw removedEntityCannotBePersisted(metadata, entity);
            }
            existing.refreshSnapshot();
            return;
        }
        identityMap.put(key, new ManagedEntry(entity, metadata, buildSnapshot(metadata, entity), false));
    }

    /**
     * 주어진 엔티티가 이미 세션에 관리 중인지.
     */
    boolean isManaged(EntityMetadata<?> metadata, Object entity) {
        EntityKey key = keyFor(metadata, entity);
        ManagedEntry entry = key == null ? null : identityMap.get(key);
        return entry != null && !entry.isRemoved();
    }

    /**
     * 주어진 객체 자체가 현재 세션의 관리 인스턴스인지 판정한다. 같은 식별자를 가진 detached 객체는
     * identity map의 엔트리와 다른 인스턴스이므로 관리 대상으로 보지 않는다.
     */
    boolean isManagedExactInstance(EntityMetadata<?> metadata, Object entity) {
        ManagedEntry entry = managedEntry(metadata, entity);
        return entry != null && !entry.isRemoved() && entry.entity() == entity;
    }

    /**
     * 정확히 같은 관리 인스턴스에만 성공한 JPA 잠금 모드를 기록한다. identity key만 같은 detached
     * 인스턴스에는 기록하지 않는다.
     */
    void recordLockModeExactInstance(EntityMetadata<?> metadata, Object entity, LockModeType lockMode) {
        ManagedEntry entry = managedEntry(metadata, entity);
        if (entry != null && !entry.isRemoved() && entry.entity() == entity) {
            entry.setLockMode(lockMode);
        }
    }

    /**
     * 정확히 같은 관리 인스턴스의 마지막 성공 잠금 모드를 반환한다. 관리되지 않은 객체는 NONE이다.
     */
    LockModeType lockModeExactInstance(EntityMetadata<?> metadata, Object entity) {
        ManagedEntry entry = managedEntry(metadata, entity);
        return entry != null && !entry.isRemoved() && entry.entity() == entity
                ? entry.lockMode()
                : LockModeType.NONE;
    }

    /**
     * 관리 중인 엔트리들을 등록 순서대로 반환한다. flush가 순회하며 dirty diff → UPDATE를 발행한다.
     */
    Collection<ManagedEntry> managedEntries() {
        return identityMap.values();
    }

    /**
     * 주어진 엔티티의 관리 엔트리를 반환한다(미관리/식별 불가면 {@code null}). 컬렉션 baseline 캡처처럼 ops가
     * 특정 엔트리에 부가 상태를 기록할 때 쓴다.
     */
    ManagedEntry managedEntry(EntityMetadata<?> metadata, Object entity) {
        EntityKey key = keyFor(metadata, entity);
        return key == null ? null : identityMap.get(key);
    }

    /**
     * 직접 UPDATE의 writeback은 identity map의 같은 키가 아니라 정확히 같은 관리 인스턴스에만 적용한다.
     * 따라서 detached same-id 인스턴스의 SQL 성공이 canonical 인스턴스의 dirty baseline을 바꾸지 않는다.
     */
    void refreshManagedExactInstanceSnapshot(
            EntityMetadata<?> metadata, Object entity, Iterable<PersistentProperty> properties) {
        ManagedEntry entry = managedEntry(metadata, entity);
        if (entry != null && !entry.isRemoved() && entry.entity() == entity) {
            entry.refreshSnapshotFields(properties);
        }
    }

    /**
     * 직접 부분 UPDATE가 기록한 저장형 값을 정확히 같은 관리 인스턴스의 baseline에 반영한다. 호출자가
     * post-callback 전에 캡처한 값을 받으므로 callback이 엔티티를 다시 바꿔도 기록되지 않은 값이 clean으로
     * 바뀌지 않는다.
     */
    void refreshManagedExactInstanceSnapshot(
            EntityMetadata<?> metadata, Object entity, Map<String, Object> storageValues) {
        ManagedEntry entry = managedEntry(metadata, entity);
        if (entry != null && !entry.isRemoved() && entry.entity() == entity) {
            entry.refreshSnapshotFields(storageValues);
        }
    }

    void refreshManagedExactInstanceSnapshot(EntityMetadata<?> metadata, Object entity) {
        ManagedEntry entry = managedEntry(metadata, entity);
        if (entry != null && !entry.isRemoved() && entry.entity() == entity) {
            entry.refreshSnapshot();
        }
    }

    boolean isRemoved(EntityMetadata<?> metadata, Object entity) {
        ManagedEntry entry = managedEntry(metadata, entity);
        return entry != null && entry.isRemoved() && entry.entity() == entity;
    }

    /**
     * 성공한 DELETE/soft-delete DML 뒤에 엔티티를 tombstone으로 전환한다. 엔트리는 identity map에 남아
     * 같은 세션 안에서의 재-persist를 명확히 거부하지만, flush/contains/lock에서는 미관리로 취급된다.
     */
    void markRemoved(EntityMetadata<?> metadata, Object entity) {
        EntityKey key = keyFor(metadata, entity);
        if (key != null) {
            ManagedEntry entry = identityMap.get(key);
            if (entry != null && entry.entity() == entity) {
                entry.markRemoved();
            }
        }
    }

    void markRemovedById(EntityMetadata<?> metadata, Object id) {
        EntityKey key = keyForId(metadata, id);
        if (key != null) {
            markRemoved(key);
            // find/deleteById may be invoked with an inheritance root while the identity map holds a concrete
            // subtype key. Match its normalized id components without weakening unrelated entity-type isolation.
            for (Map.Entry<EntityKey, ManagedEntry> candidate : identityMap.entrySet()) {
                if (metadata.entityType().isAssignableFrom(candidate.getValue().metadata().entityType())
                        && key.idValues().equals(candidate.getKey().idValues())) {
                    candidate.getValue().markRemoved();
                }
            }
        }
    }

    private void markRemoved(EntityKey key) {
        ManagedEntry entry = identityMap.get(key);
        if (entry != null) {
            entry.markRemoved();
        }
    }

    boolean isEmpty() {
        return identityMap.isEmpty();
    }

    int size() {
        return identityMap.size();
    }

    void clear() {
        identityMap.clear();
    }

    /**
     * 주어진 엔티티를 identity map에서 제거한다(JPA {@code detach} 등가). id로 식별할 수 없으면(예: null id)
     * no-op이다. 제거된 엔티티는 이후 flush 대상에서 빠지므로 미flush 변경은 폐기된다. 이 메서드는 identity map
     * 자료구조에서 한 엔트리를 지우기만 하며, 스냅샷/dirty diff 알고리즘 자체는 건드리지 않는다.
     */
    void detach(EntityMetadata<?> metadata, Object entity) {
        EntityKey key = keyFor(metadata, entity);
        if (key != null) {
            ManagedEntry entry = identityMap.get(key);
            if (entry != null && !entry.isRemoved() && entry.entity() == entity) {
                identityMap.remove(key);
            }
        }
    }

    /**
     * 엔티티의 id 컬럼 값 리스트로 identity 키를 만든다. 단일 키는 1원소, 복합키는 컴포넌트 수만큼이다.
     * 모든 컬럼 값이 null이면(아직 식별 불가) {@code null}을 반환해 관리 대상에서 제외한다.
     */
    private static EntityKey keyFor(EntityMetadata<?> metadata, Object entity) {
        return keyForId(metadata, metadata.readIdValue(entity), entity.getClass());
    }

    private static EntityKey keyForId(EntityMetadata<?> metadata, Object idObject) {
        return keyForId(metadata, idObject, metadata.entityType());
    }

    private static EntityKey keyForId(EntityMetadata<?> metadata, Object idObject, Class<?> entityType) {
        List<PersistentProperty> idProperties = metadata.idProperties();
        List<Object> values = new ArrayList<>(idProperties.size());
        boolean allNull = true;
        for (PersistentProperty idProperty : idProperties) {
            Object columnValue = metadata.idColumnValue(idProperty, idObject);
            Object stored = idProperty.toColumnValue(columnValue);
            if (stored != null) {
                allNull = false;
            }
            values.add(stored);
        }
        if (allNull) {
            return null;
        }
        return new EntityKey(entityType, Collections.unmodifiableList(values));
    }

    private static IllegalStateException removedEntityCannotBePersisted(EntityMetadata<?> metadata, Object entity) {
        return new IllegalStateException("Cannot persist removed entity " + metadata.entityType().getName()
                + " in the same persistence session; clear the session before persisting it again");
    }

    /**
     * 모든 column-mapped property에 대해 {@code columnName -> 저장형 값} 스냅샷을 만든다. 임베디드 leaf는
     * 이미 개별 컬럼 property로 평탄화되어 있고, converter 컬럼은 저장 표현으로 기록된다.
     */
    private static Map<String, Object> buildSnapshot(EntityMetadata<?> metadata, Object entity) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        for (PersistentProperty property : metadata.columnMappedProperties()) {
            snapshot.put(property.columnName(), snapshotValue(property, entity));
        }
        return snapshot;
    }

    static Object snapshotValue(PersistentProperty property, Object entity) {
        if (!property.isCompositeToOne()) {
            return property.toColumnValue(property.read(entity));
        }
        Object reference = property.readReferenceInstance(entity);
        if (reference == null) {
            return null;
        }
        List<Object> values = new ArrayList<>(property.toOneForeignKey().columns().size());
        for (var column : property.toOneForeignKey().columns()) {
            values.add(column.toColumnValue(column.readReferencedValue(reference)));
        }
        return List.copyOf(values);
    }
}
