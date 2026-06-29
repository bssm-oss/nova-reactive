package io.nova.metadata;

import jakarta.persistence.CascadeType;

import java.util.Set;

/**
 * {@code @OneToMany}의 cascade / orphanRemoval / {@code @OrderColumn} 메타데이터. marker-only
 * {@code @OneToMany}(cascade 없음, orphanRemoval=false, 순서 컬럼 없음)에는 {@code null}로 두어 기존 동작을
 * 그대로 보존하고, 셋 중 하나라도 지정된 경우에만 채워진다.
 * <p>
 * {@link #cascadeTypes()}는 어노테이션에 선언된 cascade 집합을 정규화한 것이다 — {@link CascadeType#ALL}은
 * 펼쳐서 PERSIST/MERGE/REMOVE/REFRESH/DETACH를 모두 포함시킨다. Nova의 reactive flush가 실제로 전파하는 것은
 * PERSIST/MERGE/REMOVE이며 나머지 타입은 무해하게 무시된다(no-op 영역).
 * <p>
 * {@link #orderColumn()}이 non-null이면 이 {@code @OneToMany(mappedBy)} List는 {@code @OrderColumn}으로 정렬되며,
 * 순서 정수 컬럼이 child 테이블에 위치한다. save 시 child의 order 컬럼을 0..n-1로 raw UPDATE하고, fetch 시 그
 * 컬럼으로 child 순서를 복원한다.
 */
public record OneToManyInfo(
        Set<CascadeType> cascadeTypes,
        boolean orphanRemoval,
        OrderColumnInfo orderColumn
) {
    public OneToManyInfo {
        cascadeTypes = cascadeTypes == null ? Set.of() : Set.copyOf(cascadeTypes);
    }

    /**
     * cascade/orphanRemoval만 받는 생성자 — 순서 컬럼은 없는 형태(기존 호출부 호환).
     */
    public OneToManyInfo(Set<CascadeType> cascadeTypes, boolean orphanRemoval) {
        this(cascadeTypes, orphanRemoval, null);
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

    /**
     * 이 {@code @OneToMany}가 {@code @OrderColumn}으로 정렬되면 {@code true}.
     */
    public boolean ordered() {
        return orderColumn != null;
    }
}
