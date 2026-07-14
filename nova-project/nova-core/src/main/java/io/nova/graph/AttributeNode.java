package io.nova.graph;

import java.util.Objects;

/**
 * {@code jakarta.persistence.NamedAttributeNode} 한 개를 표현하는 fetch-plan 노드다. fetch 대상 연관
 * 속성 이름과, 그 연관을 더 깊게 펼치는 {@link NamedSubgraphDefinition}의 이름(선택)을 담는다.
 *
 * <p>{@code subgraphName}이 {@code null}/blank가 아니면 이 노드는 depth&gt;1의 중첩 fetch를 요구한다.
 * {@link EntityGraphs}가 그 이름의 {@link NamedSubgraphDefinition}을 찾아 재귀적으로 {@link FetchNode}
 * 트리로 해석하며(레벨별 배치 hydration), subgraph 이름 참조가 사이클을 이루면 fail-fast한다.
 *
 * @param attributeName fetch할 연관 속성 이름(엔티티의 property 이름)
 * @param subgraphName  중첩 서브그래프 이름(없으면 {@code null})
 */
public record AttributeNode(String attributeName, String subgraphName) {

    public AttributeNode {
        Objects.requireNonNull(attributeName, "attributeName must not be null");
        if (attributeName.isBlank()) {
            throw new IllegalArgumentException("attributeName must not be blank");
        }
        if (subgraphName != null && subgraphName.isBlank()) {
            subgraphName = null;
        }
    }

    /** 속성 이름만 있는(중첩 서브그래프 없는) 노드를 만든다. */
    public static AttributeNode of(String attributeName) {
        return new AttributeNode(attributeName, null);
    }

    /** 이 노드가 중첩 서브그래프를 참조하는지 여부. */
    public boolean hasSubgraph() {
        return subgraphName != null;
    }
}
