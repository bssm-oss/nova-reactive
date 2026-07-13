package io.nova.graph;

import io.nova.fetch.AnnotationFetchGroupBuilder;
import io.nova.fetch.FetchGroup;
import io.nova.metadata.EntityMetadata;
import io.nova.metadata.EntityMetadataFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * {@link EntityGraph} 팩토리다. JPA의 {@code EntityManagerFactory.createEntityGraph(...)} /
 * {@code getEntityGraph(name)}에 해당하는 진입점을 리액티브 등가로 제공한다.
 *
 * <p><b>v1 의미(always-eager 정합):</b> Nova는 blocking lazy proxy가 없어 매핑 연관을 기본 eager 로드한다.
 * 따라서 EntityGraph는 "명명 연관을 <b>제외</b>하고 나머지를 lazy로 남기는" JPA fetch-graph 의미가 아니라,
 * "명명(및 매핑된 모든) 연관의 <b>배치(no N+1) 로드를 보장</b>하는" plan이다. 미명명 연관도 기본 eager라
 * 함께 로드된다(제외할 lazy 수단이 없음). 즉 그래프로 조회한 결과는 default eager 조회와 최소 동등 이상이다.
 *
 * <p>이 팩토리는 그래프에 명명된 속성이 실제로 존재하는지 검증(typo fail-fast)하고, 중첩 서브그래프
 * (depth&gt;1)는 flat {@link FetchGroup} 경로로 표현할 수 없어 fail-fast한다. 기본 속성/연관 어느 쪽을
 * 명명해도 허용한다 — 연관 fetch는 {@code findAll}/{@code findById}의 배치 hydration이 담당한다
 * (JPQL {@code JOIN FETCH}가 연관을 수용하는 것과 정합).
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
        return resolve(rootType, definition.name(), definition.attributeNodes());
    }

    /**
     * 프로그램적으로 속성을 지정해 ad-hoc {@link EntityGraph}를 만드는 빌더를 연다(JPA
     * {@code createEntityGraph(rootType)} 등가).
     */
    public <T> Builder<T> building(Class<T> rootType) {
        Objects.requireNonNull(rootType, "rootType must not be null");
        return new Builder<>(this, rootType);
    }

    private <T> EntityGraph<T> resolve(Class<T> rootType, String name, List<AttributeNode> nodes) {
        EntityMetadata<T> metadata = metadataFactory.getEntityMetadata(rootType);
        List<String> attributeNames = new ArrayList<>(nodes.size());
        for (AttributeNode node : nodes) {
            String attribute = node.attributeName();
            attributeNames.add(attribute);
            if (node.hasSubgraph()) {
                throw new IllegalArgumentException(
                        "Nested @NamedSubgraph fetch (attribute '" + attribute + "' -> subgraph '"
                                + node.subgraphName() + "') is not supported in v1; declare a flat EntityGraph. "
                                + "Deeper fetch depth has no equivalent in Nova's flat FetchGroup batch path.");
            }
            // 존재 검증(typo fail-fast)만 하고 종류는 제한하지 않는다 — 기본/연관 어느 쪽이든 허용한다
            // (@ManyToMany/@ElementCollection 포함, JOIN FETCH 수용과 정합). 연관 로드는 findAll/findById의
            // 배치 hydration이 담당하며, Nova는 always-eager라 명명 연관을 배제하지 않는다.
            metadata.findProperty(attribute)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "EntityGraph attribute '" + attribute + "' does not exist on "
                                    + rootType.getName()));
        }
        // always-eager: 매핑된 모든 to-one/to-many 연관을 배치 fetch하는 group을 담는다(명명 연관 ⊆ 이 group).
        // @ManyToMany/@ElementCollection 은 findAll/findById(FetchGroup) 경로의 M2M/EC hydration 이 로드한다.
        FetchGroup<T> fetchGroup = fetchGroupBuilder.buildFor(rootType);
        return new EntityGraph<>(rootType, name, attributeNames, fetchGroup);
    }

    /**
     * ad-hoc {@link EntityGraph} 빌더. 속성 이름을 누적한 뒤 {@link #build()}로 fetch plan을 해석한다.
     */
    public static final class Builder<T> {
        private final EntityGraphs owner;
        private final Class<T> rootType;
        private final List<AttributeNode> nodes = new ArrayList<>();

        private Builder(EntityGraphs owner, Class<T> rootType) {
            this.owner = owner;
            this.rootType = rootType;
        }

        /** 하나 이상의 최상위 fetch 속성을 추가한다. */
        public Builder<T> addAttributeNodes(String... attributeNames) {
            Objects.requireNonNull(attributeNames, "attributeNames must not be null");
            for (String attribute : attributeNames) {
                nodes.add(AttributeNode.of(attribute));
            }
            return this;
        }

        public EntityGraph<T> build() {
            return owner.resolve(rootType, null, nodes);
        }
    }
}
