package io.nova.graph;

import java.util.List;
import java.util.Objects;

/**
 * {@code jakarta.persistence.NamedSubgraph} 한 개를 표현한다. 서브그래프는 어떤 연관 노드를 더 깊게 fetch할
 * 때 그 대상 엔티티에서 다시 fetch할 속성들을 정의한다.
 *
 * <p>Nova v1은 서브그래프를 <b>모델로만 보존</b>하며(introspection 목적), 실제 depth&gt;1 fetch 실행은
 * flat {@link io.nova.fetch.FetchGroup} 경로로 표현할 수 없어 {@link EntityGraphs} 변환에서 fail-fast한다.
 *
 * @param name           서브그래프 이름({@link AttributeNode#subgraphName()}가 참조)
 * @param type           서브그래프가 적용될 엔티티 타입(미지정이면 {@code null})
 * @param attributeNodes 서브그래프 대상 엔티티에서 fetch할 속성 노드들
 */
public record NamedSubgraphDefinition(String name, Class<?> type, List<AttributeNode> attributeNodes) {

    public NamedSubgraphDefinition {
        Objects.requireNonNull(name, "name must not be null");
        if (name.isBlank()) {
            throw new IllegalArgumentException("subgraph name must not be blank");
        }
        attributeNodes = List.copyOf(attributeNodes);
    }
}
