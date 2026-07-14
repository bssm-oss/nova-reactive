package io.nova.r2dbc.integration;

import io.nova.core.SqlExecutionListener;
import io.nova.graph.EntityGraph;
import io.nova.graph.EntityGraphs;
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
import jakarta.persistence.NamedSubgraph;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 중첩 서브그래프(depth&gt;1) EntityGraph fetch가 H2 in-memory R2DBC driver와 end-to-end로 동작하는지 검증한다.
 *
 * <p>루트 {@code NestAuthor} → {@code books}(@OneToMany) → 각 book의 {@code publisher}(@ManyToOne)라는 2단계
 * 그래프를 {@code @NamedEntityGraph}(@NamedSubgraph) 형태와 프로그램적 {@code addSubgraph} 형태 둘 다로 조회한다.
 * depth&gt;1 지원 전에는 book.publisher가 FK-only stub(name=NULL)로만 남았음을 대조군으로 고정해 false-green을
 * 방지하고(선언 없이 flat 조회 시 stub), subgraph를 선언하면 각 레벨의 배치 SELECT로 publisher가 fully-load됨을
 * 확인한다. 부모/자식 수와 무관하게 SELECT 수가 고정(레벨당 IN-절 1회, N+1 없음)임도 {@code SqlExecutionListener}로
 * 고정한다.
 */
class NestedEntityGraphIntegrationTest {

    private H2IntegrationTestSupport support;
    private EntityGraphs graphs;
    private final RecordingListener recorder = new RecordingListener();

    @BeforeEach
    void setUp() {
        support = H2IntegrationTestSupport.createWithManagedTransactions(recorder);
        SchemaInitializer schema =
                new SimpleSchemaInitializer(support.operations(), support.metadataFactory(), support.dialect());
        schema.create(NestAuthor.class, NestBook.class, NestPublisher.class).block();
        graphs = new EntityGraphs(support.metadataFactory());

        NestPublisher oreilly = support.operations().save(new NestPublisher("oreilly")).block();
        NestPublisher manning = support.operations().save(new NestPublisher("manning")).block();

        NestAuthor ada = support.operations().save(new NestAuthor("ada")).block();
        NestAuthor ben = support.operations().save(new NestAuthor("ben")).block();

        support.operations().save(new NestBook("clean code", ada, oreilly)).block();
        support.operations().save(new NestBook("refactoring", ada, manning)).block();
        support.operations().save(new NestBook("ddd", ben, oreilly)).block();
    }

    @Test
    void flatGraphLeavesDeepRelationAsStub() {
        // 대조군(false-green 방지): subgraph 없이 flat 조회하면 book.publisher 는 FK-only stub 이라 name 이 NULL 이다.
        EntityGraph<NestAuthor> flat = graphs.building(NestAuthor.class).addAttributeNodes("books").build();
        Long adaId = adaId();

        StepVerifier.create(support.operations().findById(NestAuthor.class, adaId, flat))
                .assertNext(author -> {
                    assertEquals(2, author.getBooks().size());
                    NestBook book = author.getBooks().get(0);
                    assertNotNull(book.getPublisher(), "@ManyToOne 은 mapRow 가 FK-only stub 으로 채운다");
                    assertNull(book.getPublisher().getName(),
                            "flat 그래프는 depth>1 을 로드하지 않으므로 publisher 는 name 이 비어 있는 stub 이다");
                })
                .verifyComplete();
    }

    @Test
    void namedSubgraphFindByIdLoadsDeepPublisher() {
        Long adaId = adaId();
        EntityGraph<NestAuthor> graph = graphs.named(NestAuthor.class, "NestAuthor.booksPublisher");

        recorder.clear();
        StepVerifier.create(support.operations().findById(NestAuthor.class, adaId, graph))
                .assertNext(author -> {
                    assertEquals(2, author.getBooks().size(), "books(depth 1) 로드");
                    for (NestBook book : author.getBooks()) {
                        assertNotNull(book.getPublisher(), "각 book 의 publisher(depth 2) 로드");
                        assertNotNull(book.getPublisher().getName(),
                                "depth>1 subgraph 로 publisher 가 stub 이 아닌 fully-loaded 상태여야 한다");
                    }
                })
                .verifyComplete();

        // root(nest_author) 1 + books IN 1 + publisher IN 1 = 3. 각 레벨 SELECT 발생, lazy proxy 아님.
        assertEquals(3, recorder.selectCount(), "레벨당 IN-절 1회 — depth>1 이어도 N+1 없음");
    }

    @Test
    void programmaticAddSubgraphFindAllLoadsDeepPublisherWithoutNPlusOne() {
        EntityGraph<NestAuthor> graph = graphs.building(NestAuthor.class)
                .addSubgraph("books")
                .addAttributeNodes("publisher")
                .build();

        List<NestAuthor> authors = new ArrayList<>();
        recorder.clear();
        StepVerifier.create(support.operations().findAll(NestAuthor.class, graph))
                .recordWith(() -> authors)
                .expectNextCount(2)
                .verifyComplete();

        int totalBooks = 0;
        for (NestAuthor author : authors) {
            for (NestBook book : author.getBooks()) {
                totalBooks++;
                assertNotNull(book.getPublisher().getName(),
                        "프로그램적 addSubgraph 로도 depth 2 publisher 가 fully-load 된다");
            }
        }
        assertEquals(3, totalBooks, "저자 2명이 총 3권");

        // root 1 + books IN 1 + publisher IN 1 = 3. 저자/책/출판사 수와 무관하게 고정(N+1 없음).
        assertEquals(3, recorder.selectCount(), "부모 수와 무관하게 depth 2 SELECT 고정");
    }

    @Test
    void mixedBasicAndAssociationSubgraphLoadsAssociationWithoutFailing() {
        // JPA 이식 흔한 패턴: subgraph 에 basic(title) + association(publisher) 혼합. basic 은 이미 로드돼 있고
        // (mapRow), publisher 만 depth-2 로 로드된다 — basic 하나가 전체 nested fetch 를 깨뜨리지 않아야 한다.
        EntityGraph<NestAuthor> graph = graphs.building(NestAuthor.class)
                .addSubgraph("books")
                .addAttributeNodes("title", "publisher")
                .build();

        List<NestAuthor> authors = new ArrayList<>();
        recorder.clear();
        StepVerifier.create(support.operations().findAll(NestAuthor.class, graph))
                .recordWith(() -> authors)
                .expectNextCount(2)
                .verifyComplete();

        for (NestAuthor author : authors) {
            for (NestBook book : author.getBooks()) {
                assertNotNull(book.getTitle(), "basic title 은 mapRow 로 이미 채워져 있다");
                assertNotNull(book.getPublisher().getName(),
                        "혼합 subgraph 에서도 association publisher 는 depth-2 로 fully-load 된다");
            }
        }
        // basic title 은 추가 쿼리를 내지 않는다: root 1 + books IN 1 + publisher IN 1 = 3.
        assertEquals(3, recorder.selectCount(), "basic leaf 는 no-op — SELECT 수를 늘리지 않는다");
    }

    private Long adaId() {
        return support.operations().findAll(NestAuthor.class, io.nova.query.QuerySpec.empty())
                .filter(a -> a.getName().equals("ada"))
                .next()
                .map(NestAuthor::getId)
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
    // Fixtures — NestAuthor 1─* NestBook *─1 NestPublisher (2단계 그래프)
    // ------------------------------------------------------------------------------------

    @Entity
    @Table(name = "nest_author")
    @NamedEntityGraph(
            name = "NestAuthor.booksPublisher",
            attributeNodes = @NamedAttributeNode(value = "books", subgraph = "bookPublisher"),
            subgraphs = @NamedSubgraph(name = "bookPublisher", attributeNodes = @NamedAttributeNode("publisher")))
    public static class NestAuthor {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        @Column(name = "id")
        private Long id;
        @Column(name = "name")
        private String name;
        @OneToMany(targetEntity = NestBook.class, mappedBy = "author")
        private List<NestBook> books;

        public NestAuthor() {
        }

        public NestAuthor(String name) {
            this.name = name;
        }

        public Long getId() {
            return id;
        }

        public String getName() {
            return name;
        }

        public List<NestBook> getBooks() {
            return books;
        }
    }

    @Entity
    @Table(name = "nest_book")
    public static class NestBook {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        @Column(name = "id")
        private Long id;
        @Column(name = "title")
        private String title;
        @ManyToOne(targetEntity = NestAuthor.class)
        @JoinColumn(name = "author_id")
        private NestAuthor author;
        @ManyToOne(targetEntity = NestPublisher.class)
        @JoinColumn(name = "publisher_id")
        private NestPublisher publisher;

        public NestBook() {
        }

        public NestBook(String title, NestAuthor author, NestPublisher publisher) {
            this.title = title;
            this.author = author;
            this.publisher = publisher;
        }

        public Long getId() {
            return id;
        }

        public String getTitle() {
            return title;
        }

        public NestAuthor getAuthor() {
            return author;
        }

        public NestPublisher getPublisher() {
            return publisher;
        }
    }

    @Entity
    @Table(name = "nest_publisher")
    public static class NestPublisher {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        @Column(name = "id")
        private Long id;
        @Column(name = "name")
        private String name;

        public NestPublisher() {
        }

        public NestPublisher(String name) {
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
