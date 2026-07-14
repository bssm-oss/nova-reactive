package io.nova.graph;

import java.util.List;
import java.util.Objects;

/**
 * {@link EntityGraph}의 해석된 <b>중첩 fetch 계획</b> 트리 노드다. 한 노드는 fetch할 연관 속성 이름과, 그
 * 연관 대상 엔티티에서 <b>더 깊게</b> fetch할 자식 노드들(subgraph)을 담는다 — 즉 depth&gt;1의 eager fetch를
 * flat {@link io.nova.fetch.FetchGroup}가 표현 못 하는 부분을 재귀 트리로 표현한다.
 *
 * <p>{@link EntityGraphs}가 {@code @NamedEntityGraph}의 {@code @NamedSubgraph} 또는 프로그램적
 * {@code addSubgraph}로부터 이 트리를 만들며, 각 레벨의 속성 존재/연관 종류를 미리 검증한다. 실제 로드는
 * {@code ReactiveEntityOperations}의 EntityGraph 오버로드가 레벨마다 배치(IN-절) hydration으로 순차 수행한다
 * (N+1 없음, always-eager 정합).
 *
 * @param attributeName fetch할 연관 속성 이름(부모 엔티티의 property 이름)
 * @param children      이 연관 대상 엔티티에서 더 깊게 fetch할 노드들(없으면 빈 리스트 — depth 1 leaf)
 */
public record FetchNode(String attributeName, List<FetchNode> children) {

    public FetchNode {
        Objects.requireNonNull(attributeName, "attributeName must not be null");
        if (attributeName.isBlank()) {
            throw new IllegalArgumentException("attributeName must not be blank");
        }
        children = List.copyOf(children);
    }

    /** 자식(subgraph)이 없는 leaf 노드(depth 1)를 만든다. */
    public static FetchNode leaf(String attributeName) {
        return new FetchNode(attributeName, List.of());
    }

    /** 이 노드가 더 깊은 subgraph fetch(depth&gt;1)를 요구하는지 여부. */
    public boolean hasChildren() {
        return !children.isEmpty();
    }
}
