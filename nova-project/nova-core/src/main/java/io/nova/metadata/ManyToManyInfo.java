package io.nova.metadata;

import jakarta.persistence.CascadeType;

import java.util.Set;

/**
 * {@code @ManyToMany} 관계의 link table 매핑 메타데이터. owning side({@code @JoinTable})와
 * inverse side({@code mappedBy}) 모두 이 정보를 들고 hydration·link 동기화에 사용한다.
 * <p>
 * 컬럼 이름은 항상 <em>이 엔티티</em> 기준으로 정규화된다 — {@link #ownerForeignKeyColumn()}은 link table에서
 * 이 엔티티의 id를 가리키는 컬럼, {@link #targetForeignKeyColumn()}은 대상 엔티티의 id를 가리키는 컬럼이다.
 * inverse side는 owning side의 {@code @JoinTable}을 reflect한 뒤 두 컬럼을 swap해 이 규약을 맞춘다(hydration 대칭성).
 * <p>
 * {@link #cascadeTypes()}는 owning side에서만 의미가 있다 — link 동기화가 owning side에서만 일어나므로
 * cascade-persist/merge 전파도 owning side가 수행한다(inverse side에 cascade를 선언하면 factory가 거부한다).
 */
public record ManyToManyInfo(
        boolean owning,
        Class<?> targetType,
        String joinTableName,
        String ownerForeignKeyColumn,
        String targetForeignKeyColumn,
        String mappedBy,
        boolean usesSet,
        Set<CascadeType> cascadeTypes
) {
    public ManyToManyInfo {
        cascadeTypes = cascadeTypes == null ? Set.of() : Set.copyOf(cascadeTypes);
    }

    /**
     * cascade 없는 link 매핑용 생성자(inverse side 및 cascade 미지정 owning side).
     */
    public ManyToManyInfo(
            boolean owning,
            Class<?> targetType,
            String joinTableName,
            String ownerForeignKeyColumn,
            String targetForeignKeyColumn,
            String mappedBy,
            boolean usesSet) {
        this(owning, targetType, joinTableName, ownerForeignKeyColumn, targetForeignKeyColumn,
                mappedBy, usesSet, Set.of());
    }

    /**
     * 같은 매핑에 cascade 집합만 채워 새 인스턴스를 만든다(owning side에서 @ManyToMany(cascade=...) 반영).
     */
    public ManyToManyInfo withCascade(Set<CascadeType> cascade) {
        return new ManyToManyInfo(owning, targetType, joinTableName, ownerForeignKeyColumn,
                targetForeignKeyColumn, mappedBy, usesSet, cascade);
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
