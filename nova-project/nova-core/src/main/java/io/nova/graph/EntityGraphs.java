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

/**
 * {@link EntityGraph} 팩토리다. JPA의 {@code EntityManagerFactory.createEntityGraph(...)} /
 * {@code getEntityGraph(name)}에 해당하는 진입점을 리액티브 등가로 제공한다.
 *
 * <p>메타데이터를 알기 때문에 {@code @NamedEntityGraph} 또는 프로그램적으로 지정한 속성 이름을
 * {@link FetchGroup} spec으로 해석한다. 지원하는 연관은 {@link AnnotationFetchGroupBuilder}가 다루는
 * {@code @OneToMany}/{@code @ManyToOne}/inverse {@code @OneToOne}뿐이다. {@code @ManyToMany}/
 * {@code @ElementCollection}, 중첩 서브그래프(depth&gt;1), 미지의 속성은 조용히 무시하지 않고 fail-fast한다.
 *
 * <p>기본 속성(non-relation)을 그래프에 지정하는 것은 허용하되 no-op이다 — 기본 컬럼은 루트 SELECT에서
 * 이미 로드되므로 별도 fetch가 필요 없다.
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
        NamedEntityGraphDefinition definition = NamedEntityGraphReader.find(rootType, graphName)
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
        LinkedHashSet<String> relationNames = new LinkedHashSet<>();
        for (AttributeNode node : nodes) {
            String attribute = node.attributeName();
            attributeNames.add(attribute);
            if (node.hasSubgraph()) {
                throw new IllegalArgumentException(
                        "Nested @NamedSubgraph fetch (attribute '" + attribute + "' -> subgraph '"
                                + node.subgraphName() + "') is not supported in v1; declare a flat EntityGraph. "
                                + "Deeper fetch depth has no equivalent in Nova's flat FetchGroup batch path.");
            }
            PersistentProperty property = metadata.findProperty(attribute)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "EntityGraph attribute '" + attribute + "' does not exist on "
                                    + rootType.getName()));
            if (property.manyToMany() || property.elementCollection()) {
                throw new IllegalArgumentException(
                        "EntityGraph attribute '" + attribute + "' maps a @ManyToMany/@ElementCollection which is "
                                + "not supported by the EntityGraph fetch plan in v1 (use the collection hydration "
                                + "path via findAll/findById instead)");
            }
            if (property.manyToOne() || property.oneToMany() || property.inverseToOne()) {
                relationNames.add(attribute);
            }
            // 그 외(기본/임베디드 컬럼)는 루트 SELECT에서 이미 로드되므로 no-op.
        }
        FetchGroup<T> fetchGroup = fetchGroupBuilder.buildFor(rootType, relationNames);
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
