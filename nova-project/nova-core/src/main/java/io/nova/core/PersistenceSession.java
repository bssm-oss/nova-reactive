package io.nova.core;

import io.nova.metadata.EntityMetadata;
import io.nova.metadata.PersistentProperty;

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
    static final class ManagedEntry {
        private final Object entity;
        private final EntityMetadata<?> metadata;
        private Map<String, Object> snapshot;

        private ManagedEntry(Object entity, EntityMetadata<?> metadata, Map<String, Object> snapshot) {
            this.entity = entity;
            this.metadata = metadata;
            this.snapshot = snapshot;
        }

        Object entity() {
            return entity;
        }

        EntityMetadata<?> metadata() {
            return metadata;
        }

        /**
         * 스냅샷 대비 변경된 updatable 컬럼의 property 이름을 declaration 순서로 반환한다. 저장형
         * ({@link PersistentProperty#toColumnValue}) 값으로 비교하므로 converter 컬럼도 저장 표현 기준으로
         * 정확히 diff된다. 변경 없으면 빈 리스트.
         */
        List<String> dirtyPropertyNames() {
            List<String> changed = new ArrayList<>();
            for (PersistentProperty property : metadata.updatableProperties()) {
                Object current = property.toColumnValue(property.read(entity));
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
        identityMap.put(key, new ManagedEntry(entity, metadata, buildSnapshot(metadata, entity)));
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
            existing.refreshSnapshot();
            return;
        }
        identityMap.put(key, new ManagedEntry(entity, metadata, buildSnapshot(metadata, entity)));
    }

    /**
     * 주어진 엔티티가 이미 세션에 관리 중인지.
     */
    boolean isManaged(EntityMetadata<?> metadata, Object entity) {
        EntityKey key = keyFor(metadata, entity);
        return key != null && identityMap.containsKey(key);
    }

    /**
     * 관리 중인 엔트리들을 등록 순서대로 반환한다. flush가 순회하며 dirty diff → UPDATE를 발행한다.
     */
    Collection<ManagedEntry> managedEntries() {
        return identityMap.values();
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
     * 엔티티의 id 컬럼 값 리스트로 identity 키를 만든다. 단일 키는 1원소, 복합키는 컴포넌트 수만큼이다.
     * 모든 컬럼 값이 null이면(아직 식별 불가) {@code null}을 반환해 관리 대상에서 제외한다.
     */
    private static EntityKey keyFor(EntityMetadata<?> metadata, Object entity) {
        Object idObject = metadata.readIdValue(entity);
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
        return new EntityKey(entity.getClass(), Collections.unmodifiableList(values));
    }

    /**
     * 모든 column-mapped property에 대해 {@code columnName -> 저장형 값} 스냅샷을 만든다. 임베디드 leaf는
     * 이미 개별 컬럼 property로 평탄화되어 있고, converter 컬럼은 저장 표현으로 기록된다.
     */
    private static Map<String, Object> buildSnapshot(EntityMetadata<?> metadata, Object entity) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        for (PersistentProperty property : metadata.columnMappedProperties()) {
            snapshot.put(property.columnName(), property.toColumnValue(property.read(entity)));
        }
        return snapshot;
    }
}
