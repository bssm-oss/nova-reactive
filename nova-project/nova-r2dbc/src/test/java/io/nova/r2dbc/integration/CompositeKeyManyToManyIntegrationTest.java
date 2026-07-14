package io.nova.r2dbc.integration;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;
import io.nova.metadata.EntityMetadata;
import io.nova.metadata.JoinTableDefinition;
import io.nova.metadata.ManyToManyInfo;
import io.nova.metadata.PersistentProperty;
import io.nova.query.QuerySpec;
import io.nova.schema.SchemaInitializer;
import io.nova.schema.SimpleSchemaInitializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 복합키({@code @EmbeddedId}) owner/target를 다중컬럼 join table로 매핑하는 {@code @ManyToMany}가 H2
 * in-memory R2DBC driver와 end-to-end로 동작하는지 검증한다 — N+M 컬럼 join table DDL, save 시 full-replace
 * link 동기화, 세션 최소 diff, 양측 2-hop hydration, inverse-side/owning-side 삭제 정리. 단일키 회귀는
 * {@link ManyToManyIntegrationTest}가 커버한다.
 */
class CompositeKeyManyToManyIntegrationTest {
    private H2IntegrationTestSupport support;

    @BeforeEach
    void setUp() {
        support = H2IntegrationTestSupport.create();
        SchemaInitializer schema =
                new SimpleSchemaInitializer(support.operations(), support.metadataFactory(), support.dialect());
        schema.create(Author.class, Book.class, Reader.class, Genre.class).block();
    }

    // --- both sides composite ------------------------------------------------

    @Test
    void compositeOwnerAndCompositeTargetSaveLinkAndHydrateBothSides() {
        Book clean = support.operations().save(new Book(new BookId("978", 1L), "Clean Code")).block();
        Book pragmatic = support.operations().save(new Book(new BookId("978", 2L), "Pragmatic")).block();

        Author ada = new Author(new AuthorId("US", 10L), "Ada");
        ada.getBooks().add(clean);
        ada.getBooks().add(pragmatic);
        support.operations().save(ada).block();

        // owning side: Author.books 2건 hydration(복합 owner/target).
        StepVerifier.create(support.operations().findById(Author.class, new AuthorId("US", 10L)))
                .assertNext(author -> assertEquals(Set.of("Clean Code", "Pragmatic"),
                        author.getBooks().stream().map(Book::getTitle).collect(Collectors.toSet())))
                .verifyComplete();

        // inverse side: Book.authors를 link table로 hydration(컬럼 swap).
        StepVerifier.create(support.operations().findById(Book.class, new BookId("978", 1L)))
                .assertNext(book -> {
                    assertEquals(1, book.getAuthors().size());
                    assertEquals("Ada", book.getAuthors().iterator().next().getName());
                })
                .verifyComplete();
    }

    @Test
    void reSaveFullReplacesCompositeLinks() {
        Book a = support.operations().save(new Book(new BookId("978", 1L), "A")).block();
        Book b = support.operations().save(new Book(new BookId("978", 2L), "B")).block();
        Book c = support.operations().save(new Book(new BookId("978", 3L), "C")).block();

        Author author = new Author(new AuthorId("US", 10L), "Ada");
        author.getBooks().add(a);
        author.getBooks().add(b);
        support.operations().save(author).block();

        Author loaded = support.operations().findById(Author.class, new AuthorId("US", 10L)).block();
        loaded.getBooks().clear();
        loaded.getBooks().add(c);
        support.operations().save(loaded).block();

        StepVerifier.create(support.operations().findById(Author.class, new AuthorId("US", 10L)))
                .assertNext(found -> assertEquals(Set.of("C"),
                        found.getBooks().stream().map(Book::getTitle).collect(Collectors.toSet())))
                .verifyComplete();
    }

    @Test
    void sessionMinimalDiffAddRemoveOnCompositeLinks() {
        Book a = support.operations().save(new Book(new BookId("978", 1L), "A")).block();
        Book b = support.operations().save(new Book(new BookId("978", 2L), "B")).block();
        Book c = support.operations().save(new Book(new BookId("978", 3L), "C")).block();

        Author author = new Author(new AuthorId("US", 10L), "Ada");
        author.getBooks().add(a);
        author.getBooks().add(b);
        support.operations().save(author).block();

        // 세션 안에서 a 제거 + c 추가 → commit 후 {B, C}.
        StepVerifier.create(support.operations().inTransaction(ops ->
                        ops.findById(Author.class, new AuthorId("US", 10L))
                                .doOnNext(loaded -> {
                                    loaded.getBooks().removeIf(book -> book.getTitle().equals("A"));
                                    loaded.getBooks().add(c);
                                })
                                .then()))
                .verifyComplete();

        StepVerifier.create(support.operations().findById(Author.class, new AuthorId("US", 10L)))
                .assertNext(found -> assertEquals(Set.of("B", "C"),
                        found.getBooks().stream().map(Book::getTitle).collect(Collectors.toSet())))
                .verifyComplete();
    }

    @Test
    void deletingInverseCompositeTargetCleansUpOwningLinkRows() {
        Book a = support.operations().save(new Book(new BookId("978", 1L), "A")).block();
        Author author = new Author(new AuthorId("US", 10L), "Ada");
        author.getBooks().add(a);
        support.operations().save(author).block();

        // inverse(Book) hard delete → 이 Book을 target으로 가리키는 author_book link 행이 정리되어야 한다.
        support.operations().delete(a).block();

        StepVerifier.create(support.operations().count(
                        Author.class, QuerySpec.empty()).flatMap(ignore ->
                        support.operations().findById(Author.class, new AuthorId("US", 10L))))
                .assertNext(found -> assertEquals(0, found.getBooks().size(),
                        "inverse target 삭제 후 owner의 link 컬렉션은 비어야 한다"))
                .verifyComplete();
    }

    @Test
    void deletingOwningCompositeAuthorCleansUpLinkRows() {
        Book a = support.operations().save(new Book(new BookId("978", 1L), "A")).block();
        Author author = new Author(new AuthorId("US", 10L), "Ada");
        author.getBooks().add(a);
        support.operations().save(author).block();

        support.operations().delete(author).block();

        // owning Author 삭제 후 Book.authors는 비어야 한다(link 행 정리됨).
        StepVerifier.create(support.operations().findById(Book.class, new BookId("978", 1L)))
                .assertNext(book -> assertEquals(0, book.getAuthors().size()))
                .verifyComplete();
    }

    // --- mixed cardinality ---------------------------------------------------

    @Test
    void singleOwnerAndCompositeTargetRoundTrips() {
        Book x = support.operations().save(new Book(new BookId("978", 1L), "X")).block();
        Book y = support.operations().save(new Book(new BookId("978", 2L), "Y")).block();

        Reader reader = new Reader("bob");
        reader.getBooks().add(x);
        reader.getBooks().add(y);
        Long readerId = support.operations().save(reader).map(Reader::getId).block();

        StepVerifier.create(support.operations().findById(Reader.class, readerId))
                .assertNext(found -> assertEquals(Set.of("X", "Y"),
                        found.getBooks().stream().map(Book::getTitle).collect(Collectors.toSet())))
                .verifyComplete();
    }

    @Test
    void compositeOwnerAndSingleTargetRoundTrips() {
        Genre sci = support.operations().save(new Genre("SciFi")).block();
        Genre bio = support.operations().save(new Genre("Bio")).block();

        Author author = new Author(new AuthorId("UK", 20L), "Iris");
        author.getGenres().add(sci);
        author.getGenres().add(bio);
        support.operations().save(author).block();

        StepVerifier.create(support.operations().findById(Author.class, new AuthorId("UK", 20L)))
                .assertNext(found -> assertEquals(Set.of("SciFi", "Bio"),
                        found.getGenres().stream().map(Genre::getName).collect(Collectors.toSet())))
                .verifyComplete();
    }

    // --- DDL shape -----------------------------------------------------------

    @Test
    void compositeJoinTableDdlHasAllForeignKeyColumnsAndCompositePrimaryKey() {
        EntityMetadata<?> authorMetadata = support.metadataFactory().getEntityMetadata(Author.class);
        PersistentProperty books = authorMetadata.findProperty("books").orElseThrow();
        ManyToManyInfo info = books.manyToManyInfo();
        EntityMetadata<?> bookMetadata = support.metadataFactory().getEntityMetadata(Book.class);
        JoinTableDefinition definition = JoinTableDefinition.of(authorMetadata, info, bookMetadata);

        String ddl = support.dialect().schemaGenerator().createJoinTable(definition).toLowerCase();
        assertTrue(ddl.contains("\"a_country\""), ddl);
        assertTrue(ddl.contains("\"a_num\""), ddl);
        assertTrue(ddl.contains("\"b_group\""), ddl);
        assertTrue(ddl.contains("\"b_num\""), ddl);
        // owner varchar+bigint, target varchar+bigint (저장타입 기반).
        assertTrue(ddl.contains("\"a_country\" varchar"), ddl);
        assertTrue(ddl.contains("\"a_num\" bigint"), ddl);
        // 4개 컬럼 전체가 복합 PK.
        assertTrue(ddl.contains("primary key (\"a_country\", \"a_num\", \"b_group\", \"b_num\")"), ddl);
    }

    // --- fixtures ------------------------------------------------------------

    @Embeddable
    public static class AuthorId {
        @Column(name = "a_country")
        private String country;
        @Column(name = "a_num")
        private Long num;

        public AuthorId() {
        }

        public AuthorId(String country, Long num) {
            this.country = country;
            this.num = num;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof AuthorId that)) {
                return false;
            }
            return Objects.equals(country, that.country) && Objects.equals(num, that.num);
        }

        @Override
        public int hashCode() {
            return Objects.hash(country, num);
        }
    }

    @Embeddable
    public static class BookId {
        @Column(name = "b_group")
        private String isbnGroup;
        @Column(name = "b_num")
        private Long isbnNum;

        public BookId() {
        }

        public BookId(String isbnGroup, Long isbnNum) {
            this.isbnGroup = isbnGroup;
            this.isbnNum = isbnNum;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof BookId that)) {
                return false;
            }
            return Objects.equals(isbnGroup, that.isbnGroup) && Objects.equals(isbnNum, that.isbnNum);
        }

        @Override
        public int hashCode() {
            return Objects.hash(isbnGroup, isbnNum);
        }
    }

    @Entity
    @Table(name = "author")
    public static class Author {
        @EmbeddedId
        private AuthorId id;
        private String name;

        // 복합 owner ↔ 복합 target. join 컬럼은 참조 @Id 컬럼 순서대로(positional) 정렬된다.
        @ManyToMany
        @JoinTable(name = "author_book",
                joinColumns = {@JoinColumn(name = "a_country"), @JoinColumn(name = "a_num")},
                inverseJoinColumns = {@JoinColumn(name = "b_group"), @JoinColumn(name = "b_num")})
        private Set<Book> books = new LinkedHashSet<>();

        // 복합 owner ↔ 단일 target.
        @ManyToMany
        @JoinTable(name = "author_genre",
                joinColumns = {@JoinColumn(name = "ag_country"), @JoinColumn(name = "ag_num")},
                inverseJoinColumns = @JoinColumn(name = "genre_id"))
        private Set<Genre> genres = new LinkedHashSet<>();

        public Author() {
        }

        public Author(AuthorId id, String name) {
            this.id = id;
            this.name = name;
        }

        public AuthorId getId() {
            return id;
        }

        public String getName() {
            return name;
        }

        public Set<Book> getBooks() {
            return books;
        }

        public Set<Genre> getGenres() {
            return genres;
        }
    }

    @Entity
    @Table(name = "book")
    public static class Book {
        @EmbeddedId
        private BookId id;
        private String title;

        @ManyToMany(mappedBy = "books")
        private Set<Author> authors = new LinkedHashSet<>();

        public Book() {
        }

        public Book(BookId id, String title) {
            this.id = id;
            this.title = title;
        }

        public BookId getId() {
            return id;
        }

        public String getTitle() {
            return title;
        }

        public Set<Author> getAuthors() {
            return authors;
        }
    }

    @Entity
    @Table(name = "reader")
    public static class Reader {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;
        private String name;

        // 단일 owner ↔ 복합 target.
        @ManyToMany
        @JoinTable(name = "reader_book",
                joinColumns = @JoinColumn(name = "reader_id"),
                inverseJoinColumns = {@JoinColumn(name = "b_group"), @JoinColumn(name = "b_num")})
        private Set<Book> books = new LinkedHashSet<>();

        public Reader() {
        }

        public Reader(String name) {
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
    @Table(name = "genre")
    public static class Genre {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;
        private String name;

        public Genre() {
        }

        public Genre(String name) {
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
