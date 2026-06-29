package io.nova.r2dbc.integration;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;
import io.nova.query.NativeQuery;
import io.nova.schema.SchemaInitializer;
import io.nova.schema.SimpleSchemaInitializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * owning {@code @ManyToMany(cascade=PERSIST/MERGE)}가 H2 in-memory R2DBC driver와 end-to-end로 동작하는지
 * 검증한다 — owner save 시 transient 대상 엔티티가 link 작성 전에 먼저 영속화되고(PERSIST), cascade가 없으면
 * transient 대상이 거부된다.
 */
class ManyToManyCascadeIntegrationTest {
    private H2IntegrationTestSupport support;

    @BeforeEach
    void setUp() {
        support = H2IntegrationTestSupport.create();
        SchemaInitializer schema =
                new SimpleSchemaInitializer(support.operations(), support.metadataFactory(), support.dialect());
        schema.create(Author.class, Book.class, PlainAuthor.class).block();
    }

    private long rowCount(String table) {
        String sql = "select count(*) as cnt from " + support.dialect().quote(table);
        return support.operations()
                .queryNativeOne(NativeQuery.of(sql), row -> row.get("cnt", Long.class))
                .block();
    }

    @Test
    void cascadePersistSavesTransientTargetsThenLinks() {
        Author ada = new Author("ada");
        ada.getBooks().add(new Book("b1"));
        ada.getBooks().add(new Book("b2"));
        Author saved = support.operations().save(ada).block();

        // transient 대상이 cascade로 영속화되고 link 행이 만들어진다.
        assertEquals(2L, rowCount("book"));
        assertEquals(2L, rowCount("author_book"));

        StepVerifier.create(support.operations().findById(Author.class, saved.getId()))
                .assertNext(loaded -> {
                    Set<String> titles = loaded.getBooks().stream()
                            .map(Book::getTitle).collect(Collectors.toSet());
                    assertEquals(Set.of("b1", "b2"), titles);
                })
                .verifyComplete();
    }

    @Test
    void withoutCascadeTransientTargetIsRejected() {
        PlainAuthor plain = new PlainAuthor("noop");
        plain.getBooks().add(new Book("unsaved"));

        StepVerifier.create(support.operations().save(plain))
                .verifyError();
    }

    @Entity
    @Table(name = "author")
    public static class Author {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;
        private String name;

        @ManyToMany(cascade = CascadeType.PERSIST)
        @JoinTable(name = "author_book",
                joinColumns = @JoinColumn(name = "author_id"),
                inverseJoinColumns = @JoinColumn(name = "book_id"))
        private Set<Book> books = new LinkedHashSet<>();

        public Author() {
        }

        public Author(String name) {
            this.name = name;
        }

        public Long getId() {
            return id;
        }

        public Set<Book> getBooks() {
            return books;
        }
    }

    @Entity
    @Table(name = "plain_author")
    public static class PlainAuthor {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;
        private String name;

        @ManyToMany
        @JoinTable(name = "plain_author_book",
                joinColumns = @JoinColumn(name = "author_id"),
                inverseJoinColumns = @JoinColumn(name = "book_id"))
        private Set<Book> books = new LinkedHashSet<>();

        public PlainAuthor() {
        }

        public PlainAuthor(String name) {
            this.name = name;
        }

        public Set<Book> getBooks() {
            return books;
        }
    }

    @Entity
    @Table(name = "book")
    public static class Book {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;
        private String title;

        public Book() {
        }

        public Book(String title) {
            this.title = title;
        }

        public Long getId() {
            return id;
        }

        public String getTitle() {
            return title;
        }
    }
}
