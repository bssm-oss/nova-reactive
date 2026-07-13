package io.nova.graph;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.NamedAttributeNode;
import jakarta.persistence.NamedEntityGraph;
import jakarta.persistence.NamedEntityGraphs;
import jakarta.persistence.NamedSubgraph;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link NamedEntityGraphReader}가 {@code @NamedEntityGraph}/{@code @NamedEntityGraphs} 애너테이션(및 상속
 * root 선언)을 파싱해 {@link NamedEntityGraphDefinition}으로 변환하는지 검증한다.
 */
class NamedEntityGraphReaderTest {

    @Test
    void readsSingleGraphWithFlatAttributeNodes() {
        List<NamedEntityGraphDefinition> graphs = NamedEntityGraphReader.read(Blog.class);
        assertEquals(1, graphs.size());
        NamedEntityGraphDefinition graph = graphs.get(0);
        assertEquals("Blog.withPosts", graph.name());
        assertEquals(1, graph.attributeNodes().size());
        AttributeNode node = graph.attributeNodes().get(0);
        assertEquals("posts", node.attributeName());
        assertFalse(node.hasSubgraph());
    }

    @Test
    void readsMultipleGraphsFromContainer() {
        List<NamedEntityGraphDefinition> graphs = NamedEntityGraphReader.read(Article.class);
        assertEquals(2, graphs.size());
        assertTrue(NamedEntityGraphReader.find(Article.class, "Article.withAuthor").isPresent());
        assertTrue(NamedEntityGraphReader.find(Article.class, "Article.withComments").isPresent());
    }

    @Test
    void parsesSubgraphReferenceOnAttributeNode() {
        NamedEntityGraphDefinition graph = NamedEntityGraphReader.find(WithSub.class, "WithSub.deep").orElseThrow();
        AttributeNode node = graph.attributeNodes().get(0);
        assertEquals("child", node.attributeName());
        assertTrue(node.hasSubgraph());
        assertEquals("childGraph", node.subgraphName());
        assertTrue(graph.subgraph("childGraph").isPresent());
        assertEquals(1, graph.subgraph("childGraph").orElseThrow().attributeNodes().size());
    }

    @Test
    void walksSuperclassForNonInheritedAnnotation() {
        // @NamedEntityGraph는 @Inherited가 아니므로 root에 두면 subtype이 계층 walk로 찾아야 한다.
        List<NamedEntityGraphDefinition> graphs = NamedEntityGraphReader.read(SubBlog.class);
        assertEquals(1, graphs.size());
        assertEquals("Blog.withPosts", graphs.get(0).name());
    }

    @Test
    void returnsEmptyWhenNoGraphDeclared() {
        assertTrue(NamedEntityGraphReader.read(Plain.class).isEmpty());
    }

    @Entity
    @NamedEntityGraph(name = "Blog.withPosts", attributeNodes = @NamedAttributeNode("posts"))
    static class Blog {
        @Id
        Long id;
        String posts;
    }

    static class SubBlog extends Blog {
    }

    @Entity
    @NamedEntityGraphs({
            @NamedEntityGraph(name = "Article.withAuthor", attributeNodes = @NamedAttributeNode("author")),
            @NamedEntityGraph(name = "Article.withComments", attributeNodes = @NamedAttributeNode("comments"))
    })
    static class Article {
        @Id
        Long id;
    }

    @Entity
    @NamedEntityGraph(
            name = "WithSub.deep",
            attributeNodes = @NamedAttributeNode(value = "child", subgraph = "childGraph"),
            subgraphs = @NamedSubgraph(name = "childGraph", attributeNodes = @NamedAttributeNode("grandchild")))
    static class WithSub {
        @Id
        Long id;
    }

    @Entity
    static class Plain {
        @Id
        Long id;
    }
}
