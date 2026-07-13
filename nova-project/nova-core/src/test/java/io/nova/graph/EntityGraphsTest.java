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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link EntityGraphs}가 {@code @NamedEntityGraph}/프로그램적 그래프를 fetch plan({@link FetchGroup})으로
 * 해석하는지, 지정 연관만 spec으로 담기는지, 미지원 조합에 fail-fast하는지 검증한다.
 */
class EntityGraphsTest {

    private final EntityMetadataFactory factory = new EntityMetadataFactory(new DefaultNamingStrategy());
    private final EntityGraphs graphs = new EntityGraphs(factory);

    @Test
    void namedGraphResolvesToFetchGroupWithOnlyNamedAttribute() {
        EntityGraph<GraphAuthor> graph = graphs.named(GraphAuthor.class, "GraphAuthor.withBooks");
        assertEquals("GraphAuthor.withBooks", graph.name());
        assertSame(GraphAuthor.class, graph.rootType());
        assertEquals(List.of("books"), graph.attributeNames());

        FetchGroup<GraphAuthor> group = graph.toFetchGroup();
        assertEquals(1, group.specs().size());
        FetchGroup.FetchSpec<GraphAuthor, ?> spec = group.specs().get(0);
        assertSame(GraphBook.class, spec.childType());
        assertEquals("author_id", spec.childForeignKeyColumn());
        assertFalse(spec.single(), "@OneToMany 는 list-기반 spec");
    }

    @Test
    void programmaticBuilderResolvesNamedRelationOnly() {
        // GraphAuthor 는 books(@OneToMany) 하나뿐이지만, 명시하지 않으면 spec 이 비어야 한다.
        EntityGraph<GraphAuthor> empty = graphs.building(GraphAuthor.class).build();
        assertTrue(empty.toFetchGroup().specs().isEmpty(),
                "빈 그래프는 어떤 연관도 fetch spec 으로 담지 않는다");

        EntityGraph<GraphAuthor> withBooks =
                graphs.building(GraphAuthor.class).addAttributeNodes("books").build();
        assertEquals(1, withBooks.toFetchGroup().specs().size());
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
    void manyToManyAttributeFailsFast() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> graphs.building(FkStudent.class).addAttributeNodes("courses").build());
        assertTrue(ex.getMessage().contains("@ManyToMany"));
    }

    @Test
    void nestedSubgraphFailsFast() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> graphs.named(GraphAuthor.class, "GraphAuthor.deepBooks"));
        assertTrue(ex.getMessage().contains("Nested"));
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
                    subgraphs = @NamedSubgraph(name = "bookGraph", attributeNodes = @NamedAttributeNode("author")))
    })
    public static class GraphAuthor {
        @Id
        private Long id;
        @OneToMany(targetEntity = GraphBook.class, mappedBy = "author")
        private List<GraphBook> books;
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
}
