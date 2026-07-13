package io.nova.r2dbc.integration;

import io.nova.core.SqlExecutionListener;
import io.nova.graph.EntityGraph;
import io.nova.graph.EntityGraphs;
import io.nova.query.QuerySpec;
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
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.NamedAttributeNode;
import jakarta.persistence.NamedEntityGraph;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C3 지연 로딩 등가 — {@code @NamedEntityGraph}/{@code EntityGraph} API와 JPQL {@code JOIN FETCH}가 H2
 * in-memory R2DBC driver와 end-to-end로 동작하는지 검증한다.
 *
 * <p><b>v1 의미(always-eager):</b> Nova는 blocking lazy proxy가 없어 매핑 연관을 기본 eager 로드한다.
 * EntityGraph/JOIN FETCH는 명명 연관의 <b>배치(no N+1) 로드를 보장</b>하되 미명명 연관을 <b>제외하지
 * 않는다</b>. 아래 테스트는 (1) books 만 명명해도 awards/tags 가 함께 로드되고(graph ⊇ default),
 * (2) 부모 수와 무관하게 SELECT 수가 고정(N+1 없음)이며, (3) @ManyToMany 도 그래프 경로로 로드됨을
 * {@code SqlExecutionListener} 로 고정한다.
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
        // graph_author, graph_book, graph_award, graph_tag + @ManyToMany link table(graph_author_tags).
        schema.create(GraphAuthor.class, GraphBook.class, GraphAward.class, GraphTag.class).block();
        graphs = new EntityGraphs(support.metadataFactory());
        jpql = new JpqlExecutor(support.operations(), support.dialect(), support.metadataFactory(),
                GraphAuthor.class, GraphBook.class, GraphAward.class, GraphTag.class);

        GraphTag jvm = support.operations().save(new GraphTag("jvm")).block();
        GraphTag orm = support.operations().save(new GraphTag("orm")).block();

        GraphAuthor ada = new GraphAuthor("ada");
        ada.getTags().add(jvm);
        ada.getTags().add(orm);
        ada = support.operations().save(ada).block();
        GraphAuthor ben = support.operations().save(new GraphAuthor("ben")).block();

        support.operations().save(new GraphBook("clean code", ada)).block();
        support.operations().save(new GraphBook("refactoring", ada)).block();
        support.operations().save(new GraphBook("ddd", ben)).block();
        support.operations().save(new GraphAward("hugo", ada)).block();
        support.operations().save(new GraphAward("nebula", ben)).block();
    }

    @Test
    void namedEntityGraphBatchLoadsAllRelationsWithoutNPlusOne() {
        // withBooks 는 books 만 명명하지만 always-eager 라 awards/tags 도 로드된다(graph ⊇ default).
        EntityGraph<GraphAuthor> graph = graphs.named(GraphAuthor.class, "GraphAuthor.withBooks");
        List<GraphAuthor> authors = new ArrayList<>();

        recorder.clear();
        StepVerifier.create(support.operations().findAll(GraphAuthor.class, graph))
                .recordWith(() -> authors)
                .expectNextCount(2)
                .verifyComplete();

        authors.sort((a, b) -> a.getName().compareTo(b.getName()));
        GraphAuthor ada = authors.get(0);
        GraphAuthor ben = authors.get(1);
        assertEquals(2, ada.getBooks().size(), "ada 는 책 2권");
        assertEquals(1, ben.getBooks().size(), "ben 은 책 1권");
        assertEquals(1, ada.getAwards().size(), "미명명 @OneToMany awards 도 제외되지 않고 로드된다");
        assertEquals(1, ben.getAwards().size());
        assertEquals(Set.of("jvm", "orm"),
                ada.getTags().stream().map(GraphTag::getName).collect(Collectors.toSet()),
                "미명명 @ManyToMany tags 도 그래프 경로로 로드된다");
        assertTrue(ben.getTags().isEmpty());

        // 저자 2명이어도 SELECT 는 고정: root 1 + books IN 1 + awards IN 1 + tags(link 1 + target IN 1) = 5.
        assertEquals(5, recorder.selectCount(), "부모 수와 무관하게 SELECT 고정 — N+1 없음");
    }

    @Test
    void entityGraphFindByIdCoversAllRelations() {
        Long adaId = adaId();
        EntityGraph<GraphAuthor> graph = graphs.building(GraphAuthor.class).addAttributeNodes("books").build();

        StepVerifier.create(support.operations().findById(GraphAuthor.class, adaId, graph))
                .assertNext(author -> {
                    assertEquals(2, author.getBooks().size());
                    assertEquals(1, author.getAwards().size(), "@OneToMany awards 로드(⊇ default)");
                    assertEquals(2, author.getTags().size(), "@ManyToMany tags 로드(FetchGroup 경로 M2M hydration)");
                })
                .verifyComplete();
    }

    @Test
    void jpqlJoinFetchBatchLoadsCollectionWithoutNPlusOne() {
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
    }

    @Test
    void jpqlEntitySelectWithoutJoinFetchAlsoBatchLoads() {
        // MEDIUM(false-green 방지): JOIN FETCH 는 v1에서 validation + always-eager passthrough 다.
        // JOIN FETCH 없이도 default auto-hydration 이 동일하게 books 를 배치 로드함을 대조로 고정한다.
        StepVerifier.create(
                        jpql.createQuery("SELECT a FROM GraphAuthor a WHERE a.name = :n", GraphAuthor.class)
                                .setParameter("n", "ada")
                                .getResultList())
                .assertNext(a -> assertEquals(2, a.getBooks().size(),
                        "JOIN FETCH 없이도 always-eager 로 books 가 로드된다(JOIN FETCH=passthrough)"))
                .verifyComplete();
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

    private Long adaId() {
        return support.operations().findAll(GraphAuthor.class, QuerySpec.empty())
                .filter(a -> a.getName().equals("ada"))
                .next()
                .map(GraphAuthor::getId)
                .block();
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
        @OneToMany(targetEntity = GraphAward.class, mappedBy = "author")
        private List<GraphAward> awards;
        @ManyToMany(targetEntity = GraphTag.class)
        @JoinTable(
                name = "graph_author_tags",
                joinColumns = @JoinColumn(name = "author_id"),
                inverseJoinColumns = @JoinColumn(name = "tag_id"))
        private Set<GraphTag> tags = new LinkedHashSet<>();

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

        public List<GraphAward> getAwards() {
            return awards;
        }

        public Set<GraphTag> getTags() {
            return tags;
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

    @Entity
    @Table(name = "graph_award")
    public static class GraphAward {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        @Column(name = "id")
        private Long id;
        @Column(name = "name")
        private String name;
        @ManyToOne(targetEntity = GraphAuthor.class)
        @JoinColumn(name = "author_id")
        private GraphAuthor author;

        public GraphAward() {
        }

        public GraphAward(String name, GraphAuthor author) {
            this.name = name;
            this.author = author;
        }

        public String getName() {
            return name;
        }
    }

    @Entity
    @Table(name = "graph_tag")
    public static class GraphTag {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        @Column(name = "id")
        private Long id;
        @Column(name = "name")
        private String name;

        public GraphTag() {
        }

        public GraphTag(String name) {
            this.name = name;
        }

        public Long getId() {
            return id;
        }

        public String getName() {
            return name;
        }
    }
}
