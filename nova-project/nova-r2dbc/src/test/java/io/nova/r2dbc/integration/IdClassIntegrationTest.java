package io.nova.r2dbc.integration;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import io.nova.schema.SchemaInitializer;
import io.nova.schema.SimpleSchemaInitializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code @IdClass} 복합키 entity가 H2 in-memory R2DBC driver와 end-to-end로 round-trip 되는지 검증한다.
 * 복합키는 application-assigned이라 {@code save()}가 존재 여부를 SELECT로 확인해 insert/update를 가른다.
 */
class IdClassIntegrationTest {
    private H2IntegrationTestSupport support;

    @BeforeEach
    void setUp() {
        support = H2IntegrationTestSupport.create();
        SchemaInitializer schema =
                new SimpleSchemaInitializer(support.operations(), support.metadataFactory(), support.dialect());
        schema.create(Book.class).block();
    }

    @Test
    void insertsThenFindsByIdClassInstance() {
        Mono<Book> pipeline = support.operations().save(new Book(7L, "978-1", "Reactive"))
                .then(support.operations().findById(Book.class, new BookId(7L, "978-1")));

        StepVerifier.create(pipeline)
                .assertNext(found -> {
                    assertEquals(7L, found.getPublisherId());
                    assertEquals("978-1", found.getIsbn());
                    assertEquals("Reactive", found.getTitle());
                })
                .verifyComplete();
    }

    @Test
    void secondSaveUpdatesExistingRowInsteadOfInserting() {
        Mono<Book> pipeline = support.operations().save(new Book(9L, "978-2", "First"))
                .then(support.operations().save(new Book(9L, "978-2", "Second")))
                .then(support.operations().findById(Book.class, new BookId(9L, "978-2")));

        StepVerifier.create(pipeline)
                .assertNext(found -> assertEquals("Second", found.getTitle(),
                        "두 번째 save는 같은 복합키 row를 UPDATE 해야 한다"))
                .verifyComplete();

        StepVerifier.create(support.operations().count(Book.class, io.nova.query.QuerySpec.empty()))
                .expectNext(1L)
                .verifyComplete();
    }

    @Test
    void deletesByIdClassKey() {
        Mono<Long> pipeline = support.operations().save(new Book(3L, "978-3", "Gone"))
                .then(support.operations().delete(new Book(3L, "978-3", "Gone")))
                .then(support.operations().count(Book.class, io.nova.query.QuerySpec.empty()));

        StepVerifier.create(pipeline)
                .expectNext(0L)
                .verifyComplete();
    }

    public static class BookId {
        private Long publisherId;
        private String isbn;

        public BookId() {
        }

        public BookId(Long publisherId, String isbn) {
            this.publisherId = publisherId;
            this.isbn = isbn;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof BookId that
                    && Objects.equals(publisherId, that.publisherId) && Objects.equals(isbn, that.isbn);
        }

        @Override
        public int hashCode() {
            return Objects.hash(publisherId, isbn);
        }
    }

    @Entity
    @Table(name = "book")
    @IdClass(BookId.class)
    public static class Book {
        @Id
        @Column(name = "publisher_id")
        private Long publisherId;
        @Id
        private String isbn;
        private String title;

        public Book() {
        }

        public Book(Long publisherId, String isbn, String title) {
            this.publisherId = publisherId;
            this.isbn = isbn;
            this.title = title;
        }

        public Long getPublisherId() {
            return publisherId;
        }

        public String getIsbn() {
            return isbn;
        }

        public String getTitle() {
            return title;
        }
    }
}
