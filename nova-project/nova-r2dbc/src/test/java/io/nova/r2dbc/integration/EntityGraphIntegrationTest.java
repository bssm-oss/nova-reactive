package io.nova.r2dbc.integration;

import io.nova.core.SqlExecutionListener;
import io.nova.graph.EntityGraph;
import io.nova.graph.EntityGraphs;
import io.nova.query.jpql.JpqlExecutor;
import io.nova.schema.SchemaInitializer;
import io.nova.schema.SimpleSchemaInitializer;
import io.nova.sql.SqlStatement;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.NamedAttributeNode;
import jakarta.persistence.NamedEntityGraph;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C3 지연 로딩 등가 — {@code @NamedEntityGraph}/{@code EntityGraph} API와 JPQL {@code JOIN FETCH}가 H2
 * in-memory R2DBC driver와 end-to-end로 동작하는지 검증한다. 지정한 연관이 부모 목록당 IN-절 쿼리 한 번으로
 * 배치 로드돼 N+1이 없는지, {@code SqlExecutionListener}로 실행된 SELECT 수를 세어 확인한다.
 *
 * <p>Nova는 blocking lazy proxy가 없다(AGENTS.md #4). LAZY 등가는 EntityGraph/JOIN FETCH/FetchGroup 같은
 * 명시적 fetch plan으로 제공하며, 지정 연관은 배치로 로드된다.
 */
class EntityGraphIntegrationTest {

    private H2IntegrationTestSupport support;
    private EntityGraphs graphs;
    private JpqlExecutor jpql;
    private final RecordingListener recorder = new RecordingListener();

    @BeforeEach
    void setUp() {
        support = H2IntegrationTestSupport.createWithManagedTransactions(recorder);
        SchemaInitializer schema =
                new SimpleSchemaInitializer(support.operations(), support.metadataFactory(), support.dialect());
        schema.create(GraphAuthor.class, GraphBook.class).block();
        graphs = new EntityGraphs(support.metadataFactory());
        jpql = new JpqlExecutor(support.operations(), support.dialect(), support.metadataFactory(),
                GraphAuthor.class, GraphBook.class);

        GraphAuthor ada = support.operations().save(new GraphAuthor("ada")).block();
        GraphAuthor ben = support.operations().save(new GraphAuthor("ben")).block();
        support.operations().save(new GraphBook("clean code", ada)).block();
        support.operations().save(new GraphBook("refactoring", ada)).block();
        support.operations().save(new GraphBook("ddd", ben)).block();
    }

    @Test
    void namedEntityGraphBatchLoadsCollectionWithoutNPlusOne() {
        EntityGraph<GraphAuthor> graph = graphs.named(GraphAuthor.class, "GraphAuthor.withBooks");
        List<GraphAuthor> authors = new ArrayList<>();

        recorder.clear();
        StepVerifier.create(support.operations().findAll(GraphAuthor.class, graph))
                .recordWith(() -> authors)
                .expectNextCount(2)
                .verifyComplete();

        authors.sort((a, b) -> a.getName().compareTo(b.getName()));
        assertEquals(2, authors.get(0).getBooks().size(), "ada 는 2권");
        assertEquals(1, authors.get(1).getBooks().size(), "ben 은 1권");
        // 저자가 2명이어도 SELECT 는 root 1회 + child IN 1회 = 2회여야 한다(N+1 아님).
        assertEquals(2, recorder.selectCount(),
                "부모 수와 무관하게 root 1 + child IN 1 = 2 SELECT (N+1 회피)");
    }

    @Test
    void programmaticEntityGraphLoadsSelectedAssociation() {
        EntityGraph<GraphAuthor> graph =
                graphs.building(GraphAuthor.class).addAttributeNodes("books").build();

        StepVerifier.create(support.operations().findAll(GraphAuthor.class, graph).collectList())
                .assertNext(authors -> {
                    assertEquals(2, authors.size());
                    long totalBooks = authors.stream().mapToLong(a -> a.getBooks().size()).sum();
                    assertEquals(3, totalBooks);
                })
                .verifyComplete();
    }

    @Test
    void entityGraphFindByIdLoadsManyToOneReference() {
        Long bookId = support.operations()
                .findAll(GraphBook.class, io.nova.query.QuerySpec.empty())
                .filter(b -> b.getTitle().equals("ddd"))
                .next()
                .map(GraphBook::getId)
                .block();
        assertNotNull(bookId);

        EntityGraph<GraphBook> graph =
                graphs.building(GraphBook.class).addAttributeNodes("author").build();

        StepVerifier.create(support.operations().findById(GraphBook.class, bookId, graph))
                .assertNext(book -> {
                    assertEquals("ddd", book.getTitle());
                    assertNotNull(book.getAuthor(), "@ManyToOne author 가 fetch plan 으로 로드돼야 한다");
                    assertEquals("ben", book.getAuthor().getName());
                })
                .verifyComplete();
    }

    @Test
    void jpqlJoinFetchLoadsCollectionInEntityResult() {
        List<GraphAuthor> authors = new ArrayList<>();

        recorder.clear();
        StepVerifier.create(
                        jpql.createQuery("SELECT a FROM GraphAuthor a JOIN FETCH a.books ORDER BY a.name",
                                        GraphAuthor.class)
                                .getResultList())
                .recordWith(() -> authors)
                .expectNextCount(2)
                .verifyComplete();

        assertEquals("ada", authors.get(0).getName());
        assertEquals(2, authors.get(0).getBooks().size());
        assertEquals(1, authors.get(1).getBooks().size());
        // JOIN FETCH 도 batch hydration 경로를 재사용하므로 root 1 + child IN 1 = 2 SELECT.
        assertEquals(2, recorder.selectCount(), "JOIN FETCH 는 배치로 로드돼 N+1 이 없어야 한다");
    }

    @Test
    void jpqlJoinFetchManyToOneReference() {
        StepVerifier.create(
                        jpql.createQuery("SELECT b FROM GraphBook b JOIN FETCH b.author WHERE b.title = :t",
                                        GraphBook.class)
                                .setParameter("t", "ddd")
                                .getResultList())
                .assertNext(book -> {
                    assertNotNull(book.getAuthor());
                    assertEquals("ben", book.getAuthor().getName());
                })
                .verifyComplete();
    }

    @Test
    void jpqlJoinFetchUnknownRelationFailsFast() {
        StepVerifier.create(
                        jpql.createQuery("SELECT a FROM GraphAuthor a JOIN FETCH a.bogus", GraphAuthor.class)
                                .getResultList())
                .verifyError();
    }

    /** 실행된 SELECT 문을 세는 listener. */
    private static final class RecordingListener implements SqlExecutionListener {
        private final List<String> statements = new CopyOnWriteArrayList<>();

        @Override
        public void onBeforeExecution(SqlStatement statement) {
            statements.add(statement.sql());
        }

        void clear() {
            statements.clear();
        }

        long selectCount() {
            return statements.stream()
                    .filter(sql -> sql.stripLeading().toLowerCase(Locale.ROOT).startsWith("select"))
                    .count();
        }
    }

    // ------------------------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------------------------

    @Entity
    @Table(name = "graph_author")
    @NamedEntityGraph(name = "GraphAuthor.withBooks", attributeNodes = @NamedAttributeNode("books"))
    public static class GraphAuthor {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        @Column(name = "id")
        private Long id;
        @Column(name = "name")
        private String name;
        @OneToMany(targetEntity = GraphBook.class, mappedBy = "author")
        private List<GraphBook> books;

        public GraphAuthor() {
        }

        public GraphAuthor(String name) {
            this.name = name;
        }

        public Long getId() {
            return id;
        }

        public String getName() {
            return name;
        }

        public List<GraphBook> getBooks() {
            return books;
        }
    }

    @Entity
    @Table(name = "graph_book")
    public static class GraphBook {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        @Column(name = "id")
        private Long id;
        @Column(name = "title")
        private String title;
        @ManyToOne(targetEntity = GraphAuthor.class)
        @JoinColumn(name = "author_id")
        private GraphAuthor author;

        public GraphBook() {
        }

        public GraphBook(String title, GraphAuthor author) {
            this.title = title;
            this.author = author;
        }

        public Long getId() {
            return id;
        }

        public String getTitle() {
            return title;
        }

        public GraphAuthor getAuthor() {
            return author;
        }
    }
}
