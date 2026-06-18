package io.nova.metadata;

import jakarta.persistence.CascadeType;

import java.util.Set;

/**
 * {@code @OneToMany}의 cascade / orphanRemoval 메타데이터. marker-only {@code @OneToMany}(cascade 없음,
 * orphanRemoval=false)에는 {@code null}로 두어 기존 동작을 그대로 보존하고, cascade나 orphanRemoval이 지정된
 * 경우에만 채워진다.
 * <p>
 * {@link #cascadeTypes()}는 어노테이션에 선언된 cascade 집합을 정규화한 것이다 — {@link CascadeType#ALL}은
 * 펼쳐서 PERSIST/MERGE/REMOVE/REFRESH/DETACH를 모두 포함시킨다. Nova의 reactive flush가 실제로 전파하는 것은
 * PERSIST/MERGE/REMOVE이며 나머지 타입은 무해하게 무시된다(no-op 영역).
 */
public record OneToManyInfo(
        Set<CascadeType> cascadeTypes,
        boolean orphanRemoval
) {
    public OneToManyInfo {
        cascadeTypes = cascadeTypes == null ? Set.of() : Set.copyOf(cascadeTypes);
    }

    /**
     * parent save() 시 child를 자동 INSERT/UPDATE 전파할지. {@code PERSIST} 또는 {@code ALL}이 있으면 {@code true}.
     */
    public boolean cascadePersist() {
        return cascadeTypes.contains(CascadeType.PERSIST) || cascadeTypes.contains(CascadeType.ALL);
    }

    /**
     * parent delete() 시 child를 자동 DELETE 전파할지. {@code REMOVE} 또는 {@code ALL}이 있으면 {@code true}.
     */
    public boolean cascadeRemove() {
        return cascadeTypes.contains(CascadeType.REMOVE) || cascadeTypes.contains(CascadeType.ALL);
    }

    /**
     * session merge 시 child를 전파할지. {@code MERGE} 또는 {@code ALL}이 있으면 {@code true}. Nova의 세션
     * 모델에서 merge는 save()로 표현되므로 cascade-persist와 동일 경로를 공유한다.
     */
    public boolean cascadeMerge() {
        return cascadeTypes.contains(CascadeType.MERGE) || cascadeTypes.contains(CascadeType.ALL);
    }
}
