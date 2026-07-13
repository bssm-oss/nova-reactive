package io.nova.graph;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * {@code jakarta.persistence.NamedEntityGraph} 한 개의 파싱 결과다. {@link NamedEntityGraphReader}가
 * 엔티티 클래스의 애너테이션에서 만들어내며, {@link EntityGraphs}가 이를 실행 가능한 {@link EntityGraph}로
 * 해석(연관→{@link io.nova.fetch.FetchGroup} spec 변환)한다.
 *
 * <p>이 타입은 순수 선언 모델이므로 {@link io.nova.metadata.EntityMetadataFactory}(hub) 생성자를 건드리지
 * 않고 클래스 애너테이션에서 on-demand로 읽어들인다 — 병렬 Wave2 worktree의 {@code namedQuery*} 마커와
 * 충돌하지 않도록 {@code entityGraph*} 네임스페이스를 유지한다.
 *
 * @param name           엔티티 그래프 이름
 * @param attributeNodes 최상위 fetch 속성 노드들
 * @param subgraphs      중첩 서브그래프 정의들(모델 보존용; v1 실행은 flat만 지원)
 */
public record NamedEntityGraphDefinition(
        String name,
        List<AttributeNode> attributeNodes,
        List<NamedSubgraphDefinition> subgraphs) {

    public NamedEntityGraphDefinition {
        Objects.requireNonNull(name, "name must not be null");
        if (name.isBlank()) {
            throw new IllegalArgumentException("entity graph name must not be blank");
        }
        attributeNodes = List.copyOf(attributeNodes);
        subgraphs = List.copyOf(subgraphs);
    }

    /** 이름으로 서브그래프 정의를 찾는다. */
    public Optional<NamedSubgraphDefinition> subgraph(String subgraphName) {
        return subgraphs.stream().filter(s -> s.name().equals(subgraphName)).findFirst();
    }
}
