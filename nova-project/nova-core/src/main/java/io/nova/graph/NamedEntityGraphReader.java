package io.nova.graph;

import jakarta.persistence.NamedAttributeNode;
import jakarta.persistence.NamedEntityGraph;
import jakarta.persistence.NamedEntityGraphs;
import jakarta.persistence.NamedSubgraph;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 엔티티 클래스에서 {@code @NamedEntityGraph}/{@code @NamedEntityGraphs} 애너테이션을 읽어
 * {@link NamedEntityGraphDefinition} 목록으로 변환한다.
 *
 * <p>{@code @NamedEntityGraph}는 {@code @Inherited}가 아니므로(JPA 규약) 클래스 계층을 {@code getSuperclass}로
 * 직접 walk하며 상위에 선언된 그래프도 수집한다 — 같은 이름은 가장 하위(파생) 선언이 우선한다. 이렇게 하면
 * 상속 root에 그래프를 두어도 서브타입이 참조할 수 있다(generator 상속 해석과 동일한 패턴).
 *
 * <p>hub 파일을 건드리지 않는 정적 유틸리티다 — {@link io.nova.metadata.EntityMetadataFactory}는 이
 * reader에 얇게 위임만 한다.
 */
public final class NamedEntityGraphReader {

    private NamedEntityGraphReader() {
    }

    /** 주어진 엔티티 타입(및 상위 클래스)에 선언된 모든 named entity graph를 선언 순서로 읽는다. */
    public static List<NamedEntityGraphDefinition> read(Class<?> entityType) {
        Objects.requireNonNull(entityType, "entityType must not be null");
        // 하위 클래스가 우선하도록 type→superclass 순으로 훑되, 같은 이름은 먼저 본 것(더 하위)을 유지한다.
        Map<String, NamedEntityGraphDefinition> byName = new LinkedHashMap<>();
        for (Class<?> current = entityType; current != null && current != Object.class;
                current = current.getSuperclass()) {
            for (NamedEntityGraph annotation : declaredGraphs(current)) {
                NamedEntityGraphDefinition definition = convert(annotation);
                byName.putIfAbsent(definition.name(), definition);
            }
        }
        return List.copyOf(byName.values());
    }

    /** 이름으로 named entity graph를 찾는다. 미등록이면 empty. */
    public static Optional<NamedEntityGraphDefinition> find(Class<?> entityType, String graphName) {
        Objects.requireNonNull(graphName, "graphName must not be null");
        return read(entityType).stream().filter(g -> g.name().equals(graphName)).findFirst();
    }

    private static List<NamedEntityGraph> declaredGraphs(Class<?> type) {
        List<NamedEntityGraph> graphs = new ArrayList<>();
        NamedEntityGraphs container = type.getDeclaredAnnotation(NamedEntityGraphs.class);
        if (container != null) {
            for (NamedEntityGraph graph : container.value()) {
                graphs.add(graph);
            }
        }
        NamedEntityGraph single = type.getDeclaredAnnotation(NamedEntityGraph.class);
        if (single != null) {
            graphs.add(single);
        }
        return graphs;
    }

    private static NamedEntityGraphDefinition convert(NamedEntityGraph annotation) {
        String name = annotation.name();
        if (name == null || name.isBlank()) {
            throw new IllegalStateException(
                    "@NamedEntityGraph requires an explicit name in Nova (anonymous graphs are not supported)");
        }
        List<AttributeNode> nodes = new ArrayList<>();
        for (NamedAttributeNode node : annotation.attributeNodes()) {
            nodes.add(convert(node));
        }
        List<NamedSubgraphDefinition> subgraphs = new ArrayList<>();
        for (NamedSubgraph subgraph : annotation.subgraphs()) {
            subgraphs.add(convert(subgraph));
        }
        return new NamedEntityGraphDefinition(name, nodes, subgraphs);
    }

    private static AttributeNode convert(NamedAttributeNode node) {
        String subgraph = node.subgraph();
        return new AttributeNode(node.value(), subgraph == null || subgraph.isBlank() ? null : subgraph);
    }

    private static NamedSubgraphDefinition convert(NamedSubgraph subgraph) {
        List<AttributeNode> nodes = new ArrayList<>();
        for (NamedAttributeNode node : subgraph.attributeNodes()) {
            nodes.add(convert(node));
        }
        Class<?> type = subgraph.type() == void.class ? null : subgraph.type();
        return new NamedSubgraphDefinition(subgraph.name(), type, nodes);
    }
}
