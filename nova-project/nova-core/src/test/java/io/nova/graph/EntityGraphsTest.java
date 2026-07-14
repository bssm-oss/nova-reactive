package io.nova.graph;

import io.nova.fetch.FetchGroup;
import io.nova.metadata.DefaultNamingStrategy;
import io.nova.metadata.EntityMetadataFactory;
import io.nova.support.fixtures.FixtureEntities.FkStudent;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.NamedAttributeNode;
import jakarta.persistence.NamedEntityGraph;
import jakarta.persistence.NamedEntityGraphs;
import jakarta.persistence.NamedSubgraph;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link EntityGraphs}가 {@code @NamedEntityGraph}/프로그램적 그래프를 fetch plan({@link FetchGroup})으로
 * 해석하는지 검증한다.
 *
 * <p>v1 의미는 always-eager다: 그래프는 명명 연관의 <b>배치 로드를 보장</b>하되 미명명 연관을 <b>제외하지
 * 않는다</b>(Nova는 매핑 연관을 기본 eager 로드, 제외할 lazy 수단 없음). 따라서 {@code books}만 명명해도
 * 해석된 FetchGroup 은 루트의 모든 to-one/to-many 연관(books + awards)을 담는다.
 */
class EntityGraphsTest {

    private final EntityMetadataFactory factory = new EntityMetadataFactory(new DefaultNamingStrategy());
    private final EntityGraphs graphs = new EntityGraphs(factory);

    @Test
    void namedGraphBatchLoadsAllRootRelationsNotOnlyNamed() {
        // withBooks 는 books 만 명명하지만, always-eager 라 awards 도 fetch plan 에 포함돼야 한다.
        EntityGraph<GraphAuthor> graph = graphs.named(GraphAuthor.class, "GraphAuthor.withBooks");
        assertEquals("GraphAuthor.withBooks", graph.name());
        assertSame(GraphAuthor.class, graph.rootType());
        assertEquals(List.of("books"), graph.attributeNames(), "명명된 속성은 books 하나뿐");

        FetchGroup<GraphAuthor> group = graph.toFetchGroup();
        Set<Class<?>> childTypes = group.specs().stream()
                .map(FetchGroup.FetchSpec::childType).collect(Collectors.toSet());
        assertEquals(Set.of(GraphBook.class, GraphAward.class), childTypes,
                "그래프는 미명명 awards 도 제외하지 않는다(always-eager, graph ⊇ default)");
    }

    @Test
    void programmaticGraphAlwaysCoversRootRelations() {
        // 빈 그래프여도 always-eager 라 루트 연관 전체를 담는다(default eager 와 최소 동등).
        EntityGraph<GraphAuthor> empty = graphs.building(GraphAuthor.class).build();
        assertEquals(2, empty.toFetchGroup().specs().size());

        EntityGraph<GraphAuthor> named =
                graphs.building(GraphAuthor.class).addAttributeNodes("books").build();
        assertEquals(2, named.toFetchGroup().specs().size(),
                "books 만 명명해도 awards 포함 — 명명이 로드를 축소하지 않는다");
        assertEquals(List.of("books"), named.attributeNames());
    }

    @Test
    void singleReferenceManyToOneBecomesSingleSpec() {
        EntityGraph<GraphBook> graph = graphs.building(GraphBook.class).addAttributeNodes("author").build();
        FetchGroup<GraphBook> group = graph.toFetchGroup();
        assertEquals(1, group.specs().size());
        assertTrue(group.specs().get(0).single(), "@ManyToOne 은 단건 spec");
    }

    @Test
    void unknownAttributeFailsFast() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> graphs.building(GraphAuthor.class).addAttributeNodes("bogus").build());
        assertTrue(ex.getMessage().contains("does not exist"));
    }

    @Test
    void manyToManyAttributeIsAllowedForConsistencyWithJoinFetch() {
        // JOIN FETCH 가 M2M 연관을 수용하므로 EntityGraph 도 거부하지 않는다(두 진입점 정합).
        // M2M 로드는 findAll/findById(FetchGroup) 의 M2M hydration 이 담당한다.
        assertDoesNotThrow(() -> graphs.building(FkStudent.class).addAttributeNodes("courses").build());
    }

    @Test
    void nestedNamedSubgraphResolvesToFetchTree() {
        // depth>1: books -> (each book's) author. flat fail-fast 대신 재귀 FetchNode 트리로 해석돼야 한다.
        EntityGraph<GraphAuthor> graph = graphs.named(GraphAuthor.class, "GraphAuthor.deepBooks");
        assertTrue(graph.hasNestedFetch(), "subgraph 선언이 있으면 중첩 fetch 로 표시된다");

        List<FetchNode> tree = graph.fetchTree();
        FetchNode booksNode = tree.stream().filter(n -> n.attributeName().equals("books")).findFirst().orElseThrow();
        assertTrue(booksNode.hasChildren(), "books 는 subgraph(author) 를 가진다");
        assertEquals(List.of("author"),
                booksNode.children().stream().map(FetchNode::attributeName).collect(Collectors.toList()));
        // flat FetchGroup 은 여전히 루트 연관 전체(books+awards)를 담아 always-eager 를 보존한다.
        assertEquals(2, graph.toFetchGroup().specs().size());
    }

    @Test
    void programmaticAddSubgraphBuildsNestedTree() {
        EntityGraph<GraphAuthor> graph = graphs.building(GraphAuthor.class)
                .addSubgraph("books")
                .addAttributeNodes("author")
                .build();
        assertTrue(graph.hasNestedFetch());
        FetchNode booksNode = graph.fetchTree().stream()
                .filter(n -> n.attributeName().equals("books")).findFirst().orElseThrow();
        assertEquals(List.of("author"),
                booksNode.children().stream().map(FetchNode::attributeName).collect(Collectors.toList()));
    }

    @Test
    void cyclicNamedSubgraphFailsFast() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> graphs.named(GraphAuthor.class, "GraphAuthor.cycle"));
        assertTrue(ex.getMessage().contains("cycle"), () -> "message: " + ex.getMessage());
    }

    @Test
    void subgraphOnNonAssociationFailsFast() {
        // @Id(basic) 위에 subgraph 를 선언하면 조용히 무시하지 않고 fail-fast 한다.
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> graphs.building(GraphAuthor.class).addSubgraph("id").addAttributeNodes("bogus").build());
        assertTrue(ex.getMessage().contains("non-association"), () -> "message: " + ex.getMessage());
    }

    @Test
    void missingNamedGraphFailsFast() {
        assertThrows(IllegalArgumentException.class,
                () -> graphs.named(GraphAuthor.class, "GraphAuthor.nope"));
    }

    @Entity
    @Table(name = "graph_author")
    @NamedEntityGraphs({
            @NamedEntityGraph(name = "GraphAuthor.withBooks", attributeNodes = @NamedAttributeNode("books")),
            @NamedEntityGraph(
                    name = "GraphAuthor.deepBooks",
                    attributeNodes = @NamedAttributeNode(value = "books", subgraph = "bookGraph"),
                    subgraphs = @NamedSubgraph(name = "bookGraph", attributeNodes = @NamedAttributeNode("author"))),
            @NamedEntityGraph(
                    name = "GraphAuthor.cycle",
                    attributeNodes = @NamedAttributeNode(value = "books", subgraph = "cyc"),
                    subgraphs = @NamedSubgraph(
                            name = "cyc",
                            attributeNodes = @NamedAttributeNode(value = "author", subgraph = "cyc")))
    })
    public static class GraphAuthor {
        @Id
        private Long id;
        @OneToMany(targetEntity = GraphBook.class, mappedBy = "author")
        private List<GraphBook> books;
        @OneToMany(targetEntity = GraphAward.class, mappedBy = "author")
        private List<GraphAward> awards;
    }

    @Entity
    @Table(name = "graph_book")
    public static class GraphBook {
        @Id
        private Long id;
        @ManyToOne(targetEntity = GraphAuthor.class)
        @JoinColumn(name = "author_id")
        private GraphAuthor author;
    }

    @Entity
    @Table(name = "graph_award")
    public static class GraphAward {
        @Id
        private Long id;
        @ManyToOne(targetEntity = GraphAuthor.class)
        @JoinColumn(name = "author_id")
        private GraphAuthor author;
    }
}
