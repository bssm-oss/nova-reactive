package io.nova.metadata;

import jakarta.persistence.CascadeType;

import java.util.Set;

/**
 * {@code @ManyToOne} / {@code @OneToOne}의 cascade 메타데이터. cascade가 지정되지 않은 marker-only 단건 관계에는
 * {@code null}로 두어 기존 동작(참조 엔티티를 명시적으로 persist)을 그대로 보존하고, cascade가 선언된 경우에만 채워진다.
 * <p>
 * {@link #cascadeTypes()}는 어노테이션에 선언된 cascade 집합을 정규화한 것이다 — {@link CascadeType#ALL}은
 * 펼쳐서 PERSIST/MERGE/REMOVE/REFRESH/DETACH를 모두 포함시키는 효과를 갖는다. Nova의 reactive save/delete가 실제로
 * 전파하는 것은 PERSIST/MERGE(참조 엔티티 선저장 후 FK 세팅)와 REMOVE(참조 엔티티 삭제 전파)이며 나머지 타입은
 * 무해하게 무시된다(no-op 영역).
 * <p>
 * 단건(to-one) 관계는 컬렉션과 달리 orphanRemoval 개념이 없으므로 {@link OneToManyInfo}와 달리 orphanRemoval 필드를
 * 두지 않는다. 그 외 cascade 시맨틱은 {@link OneToManyInfo}를 그대로 미러한다.
 */
public record ToOneCascadeInfo(
        Set<CascadeType> cascadeTypes
) {
    public ToOneCascadeInfo {
        cascadeTypes = cascadeTypes == null ? Set.of() : Set.copyOf(cascadeTypes);
    }

    /**
     * owner save() 시 참조 엔티티를 자동 INSERT/UPDATE 전파할지. {@code PERSIST} 또는 {@code ALL}이 있으면 {@code true}.
     */
    public boolean cascadePersist() {
        return cascadeTypes.contains(CascadeType.PERSIST) || cascadeTypes.contains(CascadeType.ALL);
    }

    /**
     * owner delete() 시 참조 엔티티를 자동 DELETE 전파할지. {@code REMOVE} 또는 {@code ALL}이 있으면 {@code true}.
     */
    public boolean cascadeRemove() {
        return cascadeTypes.contains(CascadeType.REMOVE) || cascadeTypes.contains(CascadeType.ALL);
    }

    /**
     * session merge 시 참조 엔티티를 전파할지. {@code MERGE} 또는 {@code ALL}이 있으면 {@code true}. Nova의 세션
     * 모델에서 merge는 save()로 표현되므로 cascade-persist와 동일 경로를 공유한다.
     */
    public boolean cascadeMerge() {
        return cascadeTypes.contains(CascadeType.MERGE) || cascadeTypes.contains(CascadeType.ALL);
    }
}
