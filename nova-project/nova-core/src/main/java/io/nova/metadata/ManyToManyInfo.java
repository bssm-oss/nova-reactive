package io.nova.metadata;

import jakarta.persistence.CascadeType;

import java.util.List;
import java.util.Set;

/**
 * {@code @ManyToMany} 관계의 link table 매핑 메타데이터. owning side({@code @JoinTable})와
 * inverse side({@code mappedBy}) 모두 이 정보를 들고 hydration·link 동기화에 사용한다.
 * <p>
 * FK 컬럼은 항상 <em>이 엔티티</em> 기준으로 정규화된다 — {@link #ownerForeignKeyColumns()}은 link table에서
 * 이 엔티티의 {@code @Id}(단일 또는 복합)를 가리키는 컬럼(들), {@link #targetForeignKeyColumns()}은 대상 엔티티의
 * {@code @Id}를 가리키는 컬럼(들)이다. inverse side는 owning side의 {@code @JoinTable}을 reflect한 뒤 두 컬럼
 * 리스트를 swap해 이 규약을 맞춘다(hydration 대칭성). 각 {@link JoinColumnRef}은 참조 {@code @Id} 컴포넌트
 * 순서대로 정렬되며 join 컬럼명과 그 참조 {@code @Id} 컬럼명을 짝지어 write/read/DDL/FK가 같은 순서를 공유하게 한다.
 * <p>
 * {@link #cascadeTypes()}는 owning side에서만 의미가 있다 — link 동기화가 owning side에서만 일어나므로
 * cascade-persist/merge 전파도 owning side가 수행한다(inverse side에 cascade를 선언하면 factory가 거부한다).
 */
public record ManyToManyInfo(
        boolean owning,
        Class<?> targetType,
        String joinTableName,
        List<JoinColumnRef> ownerForeignKeyColumns,
        List<JoinColumnRef> targetForeignKeyColumns,
        String mappedBy,
        boolean usesSet,
        Set<CascadeType> cascadeTypes
) {
    public ManyToManyInfo {
        ownerForeignKeyColumns = List.copyOf(ownerForeignKeyColumns);
        targetForeignKeyColumns = List.copyOf(targetForeignKeyColumns);
        cascadeTypes = cascadeTypes == null ? Set.of() : Set.copyOf(cascadeTypes);
    }

    /**
     * link table FK 컬럼 1개의 매핑 — join table의 컬럼명과 그것이 가리키는 참조 엔티티 {@code @Id} 컬럼명.
     * 참조 컬럼명은 runtime에서 id 컴포넌트 property를 찾고 DDL FK 제약의 참조 컬럼을 정할 때 쓰인다.
     */
    public record JoinColumnRef(String columnName, String referencedColumnName) {
    }

    /**
     * cascade 없는 link 매핑용 생성자(inverse side 및 cascade 미지정 owning side).
     */
    public ManyToManyInfo(
            boolean owning,
            Class<?> targetType,
            String joinTableName,
            List<JoinColumnRef> ownerForeignKeyColumns,
            List<JoinColumnRef> targetForeignKeyColumns,
            String mappedBy,
            boolean usesSet) {
        this(owning, targetType, joinTableName, ownerForeignKeyColumns, targetForeignKeyColumns,
                mappedBy, usesSet, Set.of());
    }

    /**
     * 같은 매핑에 cascade 집합만 채워 새 인스턴스를 만든다(owning side에서 @ManyToMany(cascade=...) 반영).
     */
    public ManyToManyInfo withCascade(Set<CascadeType> cascade) {
        return new ManyToManyInfo(owning, targetType, joinTableName, ownerForeignKeyColumns,
                targetForeignKeyColumns, mappedBy, usesSet, cascade);
    }

    /**
     * 첫 owner FK 컬럼명(단일키 하위 호환). 복합키에서는 첫 컬럼만 보므로 컬럼 전체는 {@link #ownerForeignKeyColumns()}를 쓴다.
     */
    public String ownerForeignKeyColumn() {
        return ownerForeignKeyColumns.get(0).columnName();
    }

    /**
     * 첫 target FK 컬럼명(단일키 하위 호환). 복합키에서는 첫 컬럼만 보므로 컬럼 전체는 {@link #targetForeignKeyColumns()}를 쓴다.
     */
    public String targetForeignKeyColumn() {
        return targetForeignKeyColumns.get(0).columnName();
    }

    /**
     * owner 또는 target FK가 2개 이상 컬럼(복합키)인지 여부.
     */
    public boolean composite() {
        return ownerForeignKeyColumns.size() > 1 || targetForeignKeyColumns.size() > 1;
    }

    /**
     * {@code true}이면 owner save 시 transient 대상 엔티티를 link 전에 먼저 영속화한다(PERSIST/ALL).
     */
    public boolean cascadePersist() {
        return cascadeTypes.contains(CascadeType.PERSIST) || cascadeTypes.contains(CascadeType.ALL);
    }

    /**
     * {@code true}이면 이미 영속된 대상 엔티티도 owner save 시 다시 저장한다(MERGE/ALL).
     */
    public boolean cascadeMerge() {
        return cascadeTypes.contains(CascadeType.MERGE) || cascadeTypes.contains(CascadeType.ALL);
    }
}
