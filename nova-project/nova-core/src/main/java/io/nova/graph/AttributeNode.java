package io.nova.graph;

import java.util.Objects;

/**
 * {@code jakarta.persistence.NamedAttributeNode} 한 개를 표현하는 fetch-plan 노드다. fetch 대상 연관
 * 속성 이름과, 그 연관을 더 깊게 펼치는 {@link NamedSubgraphDefinition}의 이름(선택)을 담는다.
 *
 * <p>{@code subgraphName}이 {@code null}/blank가 아니면 이 노드는 depth&gt;1의 중첩 fetch를 요구하는데,
 * Nova v1의 flat {@link io.nova.fetch.FetchGroup} 경로는 이를 표현할 수 없어 변환 단계에서 fail-fast한다
 * (설계상 미지원, {@link EntityGraphs} 참고).
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
