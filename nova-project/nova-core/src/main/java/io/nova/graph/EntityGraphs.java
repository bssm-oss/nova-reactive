package io.nova.graph;

import io.nova.fetch.AnnotationFetchGroupBuilder;
import io.nova.fetch.FetchGroup;
import io.nova.metadata.EntityMetadata;
import io.nova.metadata.EntityMetadataFactory;
import io.nova.metadata.PersistentProperty;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * {@link EntityGraph} 팩토리다. JPA의 {@code EntityManagerFactory.createEntityGraph(...)} /
 * {@code getEntityGraph(name)}에 해당하는 진입점을 리액티브 등가로 제공한다.
 *
 * <p><b>v1 의미(always-eager 정합):</b> Nova는 blocking lazy proxy가 없어 매핑 연관을 기본 eager 로드한다.
 * 따라서 EntityGraph는 "명명 연관을 <b>제외</b>하고 나머지를 lazy로 남기는" JPA fetch-graph 의미가 아니라,
 * "명명(및 매핑된 모든) 연관의 <b>배치(no N+1) 로드를 보장</b>하는" plan이다. 미명명 연관도 기본 eager라
 * 함께 로드된다(제외할 lazy 수단이 없음). 즉 그래프로 조회한 결과는 default eager 조회와 최소 동등 이상이다.
 *
 * <p><b>중첩 서브그래프(depth&gt;1):</b> {@code @NamedSubgraph} 또는 프로그램적 {@link Builder#addSubgraph}로
 * 선언한 깊이 &gt;1 연관도 해석해 {@link FetchNode} 트리로 담는다 — 소비 측(EntityGraph 오버로드)이 각 레벨을
 * 배치 hydration으로 순차 로드한다. 이 팩토리는 각 레벨의 속성이 실제로 존재하는지(typo fail-fast), 그리고
 * 자식(subgraph)을 가진 속성이 엔티티 연관인지(비연관/복합키 타겟/@ElementCollection 원소 위 subgraph는
 * fail-fast) 검증한다. {@code @NamedSubgraph} 이름 참조가 사이클을 이루면(무한 재귀) fail-fast한다.
 */
public final class EntityGraphs {

    private final EntityMetadataFactory metadataFactory;
    private final AnnotationFetchGroupBuilder fetchGroupBuilder;

    public EntityGraphs(EntityMetadataFactory metadataFactory) {
        this.metadataFactory = Objects.requireNonNull(metadataFactory, "metadataFactory must not be null");
        this.fetchGroupBuilder = new AnnotationFetchGroupBuilder(metadataFactory);
    }

    /**
     * {@code @NamedEntityGraph}로 선언된 그래프를 해석한다. 이름이 없으면 fail-fast.
     */
    public <T> EntityGraph<T> named(Class<T> rootType, String graphName) {
        Objects.requireNonNull(rootType, "rootType must not be null");
        Objects.requireNonNull(graphName, "graphName must not be null");
        // hub factory를 실제 접근점으로 사용한다(@NamedEntityGraph 읽기는 factory가 노출).
        NamedEntityGraphDefinition definition = metadataFactory.entityGraphDefinitions(rootType).stream()
                .filter(g -> g.name().equals(graphName))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "No @NamedEntityGraph named '" + graphName + "' declared on "
                                + rootType.getName() + " (or its superclasses)"));
        List<NodeSpec> specs = fromNamedNodes(definition, definition.attributeNodes(), new LinkedHashSet<>());
        return resolve(rootType, definition.name(), specs);
    }

    /**
     * 프로그램적으로 속성을 지정해 ad-hoc {@link EntityGraph}를 만드는 빌더를 연다(JPA
     * {@code createEntityGraph(rootType)} 등가).
     */
    public <T> Builder<T> building(Class<T> rootType) {
        Objects.requireNonNull(rootType, "rootType must not be null");
        return new Builder<>(this, rootType);
    }

    /**
     * {@code @NamedEntityGraph}의 {@link AttributeNode}(subgraph 이름 참조)를 프로그램적 {@link NodeSpec}
     * 트리로 변환한다. {@code visitedSubgraphs}는 현재 경로의 subgraph 이름 집합으로, 자기/상호 참조 사이클을
     * fail-fast한다(무한 재귀 방지).
     */
    private static List<NodeSpec> fromNamedNodes(
            NamedEntityGraphDefinition definition, List<AttributeNode> nodes, Set<String> visitedSubgraphs) {
        List<NodeSpec> specs = new ArrayList<>(nodes.size());
        for (AttributeNode node : nodes) {
            NodeSpec spec = new NodeSpec(node.attributeName());
            if (node.hasSubgraph()) {
                String subgraphName = node.subgraphName();
                if (visitedSubgraphs.contains(subgraphName)) {
                    throw new IllegalArgumentException(
                            "@NamedSubgraph '" + subgraphName + "' forms a cycle in entity graph '"
                                    + definition.name() + "' (subgraph references itself directly or transitively); "
                                    + "cyclic fetch graphs are not supported (unbounded fetch depth).");
                }
                NamedSubgraphDefinition subgraph = definition.subgraph(subgraphName)
                        .orElseThrow(() -> new IllegalArgumentException(
                                "Attribute '" + node.attributeName() + "' in entity graph '" + definition.name()
                                        + "' references @NamedSubgraph '" + subgraphName
                                        + "' which is not declared on the graph"));
                Set<String> nextVisited = new LinkedHashSet<>(visitedSubgraphs);
                nextVisited.add(subgraphName);
                spec.children.addAll(fromNamedNodes(definition, subgraph.attributeNodes(), nextVisited));
            }
            specs.add(spec);
        }
        return specs;
    }

    private <T> EntityGraph<T> resolve(Class<T> rootType, String name, List<NodeSpec> specs) {
        List<String> attributeNames = new ArrayList<>(specs.size());
        for (NodeSpec spec : specs) {
            attributeNames.add(spec.attributeName);
        }
        List<FetchNode> fetchTree = resolveTree(rootType, specs, false);
        // always-eager: 매핑된 모든 to-one/to-many 연관을 배치 fetch하는 group을 담는다(명명 연관 ⊆ 이 group).
        // @ManyToMany/@ElementCollection 은 findAll/findById(FetchGroup) 경로의 M2M/EC hydration 이 로드한다.
        // 중첩(depth>1)은 fetchTree 가 담고, EntityGraph 오버로드가 flat group 로드 후 레벨별로 hydrate한다.
        FetchGroup<T> fetchGroup = fetchGroupBuilder.buildFor(rootType);
        return new EntityGraph<>(rootType, name, attributeNames, fetchGroup, fetchTree);
    }

    /**
     * 주어진 엔티티 타입에서 각 {@link NodeSpec}을 검증하며 {@link FetchNode} 트리로 해석한다. 속성 존재
     * (typo fail-fast)를 확인하고, 자식(subgraph)을 가진 속성은 엔티티 연관이어야 하며 그 대상 타입으로 재귀한다.
     *
     * <p>leaf 노드는 <b>종류로 분류</b>한다 — 배치 로드 가능한 연관({@code @ManyToOne} 단일키/{@code @OneToMany}/
     * inverse {@code @OneToOne}/{@code @ManyToMany})만 트리에 유지하고, basic 속성 leaf는 fetch 대상이 아니므로
     * <b>드롭(no-op)</b>한다(mapRow가 이미 컬럼을 채우므로 "선언했는데 안 fetch"의 silent 누락이 아니다). 이렇게
     * 하면 흔한 JPA 이식 패턴인 basic+association 혼합 subgraph가 basic 하나 때문에 hydration 시점에 전체 실패하지
     * 않는다. depth&gt;1({@code nested})에서 배치 표현이 없는 {@code @ElementCollection}/복합키 타겟 to-one leaf는
     * query-time이 아니라 <b>build-time fail-fast</b>로 거부한다(root 레벨에선 always-eager/mapRow가 로드하므로 드롭).
     *
     * @param nested 이 레벨이 subgraph 안(depth&gt;1)인지 여부. {@code false}면 root 레벨(always-eager가 로드).
     */
    private List<FetchNode> resolveTree(Class<?> entityType, List<NodeSpec> specs, boolean nested) {
        EntityMetadata<?> metadata = metadataFactory.getEntityMetadata(entityType);
        List<FetchNode> nodes = new ArrayList<>(specs.size());
        for (NodeSpec spec : specs) {
            PersistentProperty property = metadata.findProperty(spec.attributeName)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "EntityGraph attribute '" + spec.attributeName + "' does not exist on "
                                    + entityType.getName()));
            if (spec.children.isEmpty()) {
                resolveLeaf(entityType, spec.attributeName, property, nested).ifPresent(nodes::add);
                continue;
            }
            Class<?> targetType = subgraphTargetType(entityType, spec.attributeName, property);
            List<FetchNode> children = resolveTree(targetType, spec.children, true);
            nodes.add(new FetchNode(spec.attributeName, children));
        }
        return nodes;
    }

    /**
     * leaf(자식 없는) spec을 종류로 분류해 트리에 넣을 {@link FetchNode}를 결정한다. 배치 로드 가능한 연관은
     * leaf로 유지하고, basic 속성은 드롭(빈 Optional), depth&gt;1에서 배치 표현이 없는 종류는 fail-fast한다.
     */
    private static java.util.Optional<FetchNode> resolveLeaf(
            Class<?> entityType, String attributeName, PersistentProperty property, boolean nested) {
        if (isFetchableAssociation(property)) {
            // to-one(단일키)/to-many/inverse @OneToOne/@ManyToMany 은 (root: always-eager, nested: 배치 hydration)
            // 로 로드 가능하므로 leaf로 유지한다.
            return java.util.Optional.of(FetchNode.leaf(attributeName));
        }
        if (property.elementCollection()) {
            if (nested) {
                throw new IllegalArgumentException(
                        "EntityGraph subgraph attribute '" + entityType.getName() + "." + attributeName
                                + "' is an @ElementCollection; nested (depth>1) fetch of an element collection is not "
                                + "supported. Declare it at the root of the graph (loaded eagerly) instead.");
            }
            // root: always-eager @ElementCollection hydration이 이미 로드하므로 fetch tree에서 제외(no-op).
            return java.util.Optional.empty();
        }
        if (property.isCompositeToOne()) {
            if (nested) {
                throw new IllegalArgumentException(
                        "EntityGraph subgraph attribute '" + entityType.getName() + "." + attributeName
                                + "' targets a composite-key (@EmbeddedId/@IdClass) entity; nested (depth>1) fetch "
                                + "through a multi-column FK to-one is not supported.");
            }
            // root: mapRow가 복합 id stub을 이미 채우므로 fetch tree에서 제외(no-op).
            return java.util.Optional.empty();
        }
        // basic 속성: fetch 대상이 아님 — root/nested 모두 드롭한다. mapRow가 이미 컬럼을 채우므로 silent 누락이
        // 아니며, basic 하나가 유효한 deep association fetch를 깨뜨리지 않게 한다.
        return java.util.Optional.empty();
    }

    /**
     * depth&gt;1 배치 hydration으로 로드 가능한 연관인지 — {@code @ManyToOne}(단일키)/{@code @OneToMany}/
     * inverse {@code @OneToOne}/{@code @ManyToMany}. 복합키 타겟 to-one은 다중컬럼 FK라 단일 IN 배치가 불가해
     * 제외한다(별도 분기에서 처리).
     */
    private static boolean isFetchableAssociation(PersistentProperty property) {
        if (property.manyToMany()) {
            return true;
        }
        if (property.oneToMany() || property.inverseToOne()) {
            return true;
        }
        return property.manyToOne() && !property.isCompositeToOne();
    }

    /**
     * subgraph를 선언한 속성의 대상 엔티티 타입을 해석한다. to-one/to-many/@ManyToMany 연관만 더 깊게 fetch할
     * 수 있으며, 비연관 속성/복합키 타겟 to-one/@ElementCollection(비-엔티티 원소) 위의 subgraph는 fail-fast한다
     * (조용한 무시 금지).
     */
    private static Class<?> subgraphTargetType(Class<?> entityType, String attributeName, PersistentProperty property) {
        if (property.isCompositeToOne()) {
            throw new IllegalArgumentException(
                    "EntityGraph subgraph on '" + entityType.getName() + "." + attributeName
                            + "' targets a composite-key (@EmbeddedId/@IdClass) entity; nested fetch through a "
                            + "multi-column FK to-one is not supported.");
        }
        if (property.manyToOne()) {
            return property.manyToOneTargetType();
        }
        if (property.oneToMany() || property.inverseToOne()) {
            return property.oneToManyTargetType();
        }
        if (property.manyToMany()) {
            return property.manyToManyInfo().targetType();
        }
        if (property.elementCollection()) {
            throw new IllegalArgumentException(
                    "EntityGraph subgraph on '" + entityType.getName() + "." + attributeName
                            + "' is an @ElementCollection whose elements are not entities; a subgraph can only be "
                            + "declared on an entity association (@ManyToOne/@OneToMany/@OneToOne/@ManyToMany).");
        }
        throw new IllegalArgumentException(
                "EntityGraph subgraph declared on non-association attribute '" + entityType.getName() + "."
                        + attributeName + "'; a subgraph can only be declared on an entity association.");
    }

    /**
     * ad-hoc {@link EntityGraph} 빌더. 최상위 fetch 속성을 누적하고, {@link #addSubgraph(String)}로 중첩
     * (depth&gt;1) fetch를 선언한 뒤 {@link #build()}로 fetch plan을 해석한다.
     */
    public static final class Builder<T> {
        private final EntityGraphs owner;
        private final Class<T> rootType;
        private final List<NodeSpec> nodes = new ArrayList<>();

        private Builder(EntityGraphs owner, Class<T> rootType) {
            this.owner = owner;
            this.rootType = rootType;
        }

        /** 하나 이상의 최상위 fetch 속성을 추가한다(중첩 없는 flat 노드). */
        public Builder<T> addAttributeNodes(String... attributeNames) {
            Objects.requireNonNull(attributeNames, "attributeNames must not be null");
            for (String attribute : attributeNames) {
                nodes.add(new NodeSpec(attribute));
            }
            return this;
        }

        /**
         * 최상위 연관 속성에 대한 중첩 서브그래프를 연다(JPA {@code EntityGraph.addSubgraph(attr)} 등가). 반환된
         * {@link SubgraphBuilder}에 그 연관 대상 엔티티에서 더 깊게 fetch할 속성/서브그래프를 추가한다.
         */
        public SubgraphBuilder<T> addSubgraph(String attributeName) {
            Objects.requireNonNull(attributeName, "attributeName must not be null");
            NodeSpec node = new NodeSpec(attributeName);
            nodes.add(node);
            return new SubgraphBuilder<>(this, node);
        }

        public EntityGraph<T> build() {
            return owner.resolve(rootType, null, nodes);
        }
    }

    /**
     * 중첩 서브그래프 빌더. 한 연관 속성의 대상 엔티티에서 더 깊게 fetch할 속성/서브그래프를 누적한다. 루트
     * {@link Builder}의 {@link Builder#build()}가 전체 트리를 해석하므로, 어느 레벨에서든 {@link #build()}로
     * 종료할 수 있다(내부적으로 루트 빌더에 위임).
     */
    public static final class SubgraphBuilder<T> {
        private final Builder<T> root;
        private final NodeSpec node;

        private SubgraphBuilder(Builder<T> root, NodeSpec node) {
            this.root = root;
            this.node = node;
        }

        /** 이 서브그래프 대상 엔티티에서 fetch할 flat 속성들을 추가한다. */
        public SubgraphBuilder<T> addAttributeNodes(String... attributeNames) {
            Objects.requireNonNull(attributeNames, "attributeNames must not be null");
            for (String attribute : attributeNames) {
                node.children.add(new NodeSpec(attribute));
            }
            return this;
        }

        /** 이 서브그래프 대상 엔티티의 연관 속성에 대해 더 깊은 서브그래프를 연다(depth 계속 증가). */
        public SubgraphBuilder<T> addSubgraph(String attributeName) {
            Objects.requireNonNull(attributeName, "attributeName must not be null");
            NodeSpec child = new NodeSpec(attributeName);
            node.children.add(child);
            return new SubgraphBuilder<>(root, child);
        }

        /** 루트 그래프 빌더로 돌아간다(형제 최상위 노드를 더 추가할 때). */
        public Builder<T> and() {
            return root;
        }

        /** 전체 그래프를 해석한다(루트 빌더에 위임). */
        public EntityGraph<T> build() {
            return root.build();
        }
    }

    /**
     * 검증 전 중간 표현 — 속성 이름과 그 하위(subgraph) 노드들의 가변 트리다. named/프로그램적 두 진입점이
     * 공통으로 이 트리를 만들고 {@link #resolveTree}가 엔티티 타입과 대조해 immutable {@link FetchNode}로 굳힌다.
     */
    private static final class NodeSpec {
        private final String attributeName;
        private final List<NodeSpec> children = new ArrayList<>();

        private NodeSpec(String attributeName) {
            Objects.requireNonNull(attributeName, "attributeName must not be null");
            if (attributeName.isBlank()) {
                throw new IllegalArgumentException("attributeName must not be blank");
            }
            this.attributeName = attributeName;
        }
    }
}
