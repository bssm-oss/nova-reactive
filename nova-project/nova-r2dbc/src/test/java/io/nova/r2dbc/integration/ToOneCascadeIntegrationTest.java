package io.nova.r2dbc.integration;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import io.nova.query.QuerySpec;
import io.nova.schema.SchemaInitializer;
import io.nova.schema.SimpleSchemaInitializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * {@code @ManyToOne(cascade=...)} / 소유측 {@code @OneToOne(cascade=...)}가 H2 in-memory R2DBC driver와
 * end-to-end로 동작하는지 검증한다 — (a) cascade PERSIST가 owner save 시 참조 엔티티를 먼저 INSERT해 generated id를
 * 확보하고 owner FK를 그 id로 바인딩하는지, (b) cascade REMOVE가 owner delete 시 참조 엔티티를 DELETE 전파하는지,
 * (c) {@code @OneToOne(cascade=ALL)}도 동일하게 동작하는지, (d) cascade 없는 marker-only {@code @ManyToOne}은
 * 참조를 자동 저장하지 않는(회귀) 것을 보장한다.
 *
 * <p>cycle 메모리(feedback_integration_test_surfaces_bugs.md)에 따라 SQL string unit test만으로는 reactive
 * cascade 순서(참조 선저장→owner FK 바인딩)/FK 바인딩/row decoding 호환을 보장할 수 없으므로 in-memory driver
 * integration test로 보호한다.
 */
class ToOneCascadeIntegrationTest {
    private H2IntegrationTestSupport support;

    @BeforeEach
    void setUp() {
        support = H2IntegrationTestSupport.create();
        SchemaInitializer schema =
                new SimpleSchemaInitializer(support.operations(), support.metadataFactory(), support.dialect());
        schema.create(
                Author.class, Article.class,
                Profile.class, Account.class,
                MarkerAuthor.class, MarkerArticle.class,
                Node.class).block();
    }

    @Test
    void bidirectionalToOneCascadeDoesNotRecurseInfinitely() {
        // 회귀: 양방향 cascade=ALL(self-referential) 사이클은 visited-set 가드가 없으면 무한 재귀(StackOverflow).
        // 가드가 있으면 정상 완료하고 두 행이 모두 저장돼야 한다(FK 바인딩은 ordering상 한쪽만 채워질 수 있음).
        Node a = new Node("a");
        Node b = new Node("b");
        a.setPeer(b);
        b.setPeer(a);

        StepVerifier.create(support.operations().save(a))
                .assertNext(saved -> assertNotNull(saved.getId()))
                .verifyComplete();

        StepVerifier.create(support.operations().count(Node.class, QuerySpec.empty()))
                .assertNext(count -> assertEquals(2L, count, "사이클 cascade는 크래시 없이 두 노드를 저장해야 한다"))
                .verifyComplete();
    }

    @Test
    void selfReferenceToOneCascadeDoesNotRecurseInfinitely() {
        Node n = new Node("self");
        n.setPeer(n);
        StepVerifier.create(support.operations().save(n))
                .assertNext(saved -> assertNotNull(saved.getId()))
                .verifyComplete();
    }

    @Test
    void manyToOneCascadePersistInsertsReferenceFirstAndBindsForeignKey() {
        Author author = new Author("Octavia");
        Article article = new Article("Dawn", author);

        Long articleId = support.operations().save(article).map(Article::getId).block();
        assertNotNull(articleId);
        // 참조 author가 owner article INSERT 전에 먼저 저장돼 generated id를 확보해야 한다.
        assertNotNull(article.getAuthor().getId(), "cascade-persist된 참조는 generated id를 가져야 한다");

        // author row가 정확히 1건 INSERT됐는지.
        StepVerifier.create(support.operations().count(Author.class, QuerySpec.empty()))
                .assertNext(count -> assertEquals(1L, count))
                .verifyComplete();

        // 다시 로드하면 FK(author_id)가 author id로 바인딩돼 stub 참조에 id가 채워져야 한다.
        Article loaded = support.operations().findById(Article.class, articleId).block();
        assertNotNull(loaded);
        assertNotNull(loaded.getAuthor());
        assertEquals(article.getAuthor().getId(), loaded.getAuthor().getId(),
                "article의 FK는 cascade-persist된 author id로 바인딩돼야 한다");
    }

    @Test
    void manyToOneCascadeRemoveDeletesReferenceOnOwnerDelete() {
        Author author = new Author("Ursula");
        Article article = new Article("Earthsea", author);
        Article saved = support.operations().save(article).block();

        StepVerifier.create(support.operations().count(Author.class, QuerySpec.empty()))
                .assertNext(count -> assertEquals(1L, count))
                .verifyComplete();

        // owner article delete → 참조 author도 cascade REMOVE(=ALL)로 함께 삭제돼야 한다.
        StepVerifier.create(support.operations().delete(saved))
                .expectNextCount(1)
                .verifyComplete();

        StepVerifier.create(support.operations().count(Article.class, QuerySpec.empty()))
                .assertNext(count -> assertEquals(0L, count))
                .verifyComplete();
        StepVerifier.create(support.operations().count(Author.class, QuerySpec.empty()))
                .assertNext(count -> assertEquals(0L, count, "cascade=ALL 참조는 owner delete 시 함께 삭제돼야 한다"))
                .verifyComplete();
    }

    @Test
    void oneToOneOwningCascadePersistAndRemove() {
        Profile profile = new Profile("bio");
        Account account = new Account("alice", profile);

        Long accountId = support.operations().save(account).map(Account::getId).block();
        assertNotNull(accountId);
        assertNotNull(account.getProfile().getId(), "owning @OneToOne(cascade=ALL) 참조도 먼저 저장돼야 한다");

        StepVerifier.create(support.operations().count(Profile.class, QuerySpec.empty()))
                .assertNext(count -> assertEquals(1L, count))
                .verifyComplete();

        // owner account delete → profile도 함께 삭제.
        support.operations().delete(account).block();
        StepVerifier.create(support.operations().count(Profile.class, QuerySpec.empty()))
                .assertNext(count -> assertEquals(0L, count, "owning @OneToOne(cascade=ALL)은 delete를 전파해야 한다"))
                .verifyComplete();
    }

    @Test
    void markerOnlyManyToOneDoesNotCascadePersistReference() {
        MarkerAuthor author = new MarkerAuthor("Nobody");
        MarkerArticle article = new MarkerArticle("Untitled", author);

        // cascade 없는 marker-only @ManyToOne은 참조를 자동 저장하지 않는다(회귀 가드).
        // 참조가 미영속(id=null)이면 FK가 null로 들어가므로 nullable FK여야 한다.
        support.operations().save(article).block();

        StepVerifier.create(support.operations().count(MarkerAuthor.class, QuerySpec.empty()))
                .assertNext(count -> assertEquals(0L, count, "marker-only @ManyToOne은 참조를 INSERT하지 않아야 한다"))
                .verifyComplete();
    }

    // --- @ManyToOne cascade fixtures ---------------------------------------

    @Entity
    @Table(name = "cascade_author")
    public static class Author {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;
        private String name;

        public Author() {
        }

        public Author(String name) {
            this.name = name;
        }

        public Long getId() {
            return id;
        }

        public String getName() {
            return name;
        }
    }

    @Entity
    @Table(name = "cascade_article")
    public static class Article {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;
        private String title;

        @ManyToOne(targetEntity = Author.class, cascade = CascadeType.ALL)
        @JoinColumn(name = "author_id")
        private Author author;

        public Article() {
        }

        public Article(String title, Author author) {
            this.title = title;
            this.author = author;
        }

        public Long getId() {
            return id;
        }

        public Author getAuthor() {
            return author;
        }
    }

    // --- owning @OneToOne cascade fixtures ---------------------------------

    @Entity
    @Table(name = "cascade_profile")
    public static class Profile {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;
        private String bio;

        public Profile() {
        }

        public Profile(String bio) {
            this.bio = bio;
        }

        public Long getId() {
            return id;
        }
    }

    @Entity
    @Table(name = "cascade_account")
    public static class Account {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;
        private String username;

        @OneToOne(targetEntity = Profile.class, cascade = CascadeType.ALL)
        @JoinColumn(name = "profile_id")
        private Profile profile;

        public Account() {
        }

        public Account(String username, Profile profile) {
            this.username = username;
            this.profile = profile;
        }

        public Long getId() {
            return id;
        }

        public Profile getProfile() {
            return profile;
        }
    }

    // --- marker-only @ManyToOne (no cascade) regression fixtures -----------

    @Entity
    @Table(name = "marker_author")
    public static class MarkerAuthor {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;
        private String name;

        public MarkerAuthor() {
        }

        public MarkerAuthor(String name) {
            this.name = name;
        }

        public Long getId() {
            return id;
        }
    }

    @Entity
    @Table(name = "marker_article")
    public static class MarkerArticle {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;
        private String title;

        // cascade 없는 marker-only @ManyToOne — 참조 전파가 일어나면 안 된다. FK는 nullable.
        @ManyToOne(targetEntity = MarkerAuthor.class)
        @JoinColumn(name = "author_id")
        private MarkerAuthor author;

        public MarkerArticle() {
        }

        public MarkerArticle(String title, MarkerAuthor author) {
            this.title = title;
            this.author = author;
        }

        public Long getId() {
            return id;
        }

        public MarkerAuthor getAuthor() {
            return author;
        }
    }

    // --- self-referential cascade=ALL cycle fixture ------------------------

    @Entity
    @Table(name = "cascade_node")
    public static class Node {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;
        private String name;

        // self-referential @ManyToOne(cascade=ALL). FK nullable이라 사이클에서 한쪽 미바인딩 허용.
        @ManyToOne(targetEntity = Node.class, cascade = CascadeType.ALL)
        @JoinColumn(name = "peer_id")
        private Node peer;

        public Node() {
        }

        public Node(String name) {
            this.name = name;
        }

        public Long getId() {
            return id;
        }

        public void setPeer(Node peer) {
            this.peer = peer;
        }
    }
}
