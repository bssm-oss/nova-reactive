package io.nova.r2dbc.integration;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import io.nova.fetch.FetchGroup;
import io.nova.query.QuerySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 어노테이션 기반 관계가 H2 in-memory R2DBC driver와 실제로 round-trip 되는지 검증한다.
 * cycle 8 F4 회귀 메모리(feedback_integration_test_surfaces_bugs.md)에 따라 SQL string unit test만으로는
 * row decoding/column binding 호환을 보장할 수 없으므로 별도 integration test로 보호한다.
 *
 * <p>seed row는 raw SQL로 삽입한다 — relation entity의 save()가 R2DBC driver와 어떻게 상호작용하는지는
 * 별도 cycle 작업이며 이 테스트의 scope는 read-side hydration 정확도에 한정된다.
 */
class AnnotationRelationIntegrationTest {
    private H2IntegrationTestSupport support;

    @BeforeEach
    void setUp() {
        support = H2IntegrationTestSupport.create();
        support.execute("create table \"int_author\" ("
                + "\"id\" bigint primary key, "
                + "\"name\" varchar(255))");
        support.execute("create table \"int_book\" ("
                + "\"id\" bigint primary key, "
                + "\"title\" varchar(255), "
                + "\"author_id\" bigint)");
        support.execute("insert into \"int_author\" (\"id\", \"name\") values (1, 'ada')");
        support.execute("insert into \"int_author\" (\"id\", \"name\") values (2, 'ben')");
        support.execute("insert into \"int_book\" (\"id\", \"title\", \"author_id\") values (10, 'x', 1)");
        support.execute("insert into \"int_book\" (\"id\", \"title\", \"author_id\") values (11, 'y', 1)");
        support.execute("insert into \"int_book\" (\"id\", \"title\", \"author_id\") values (20, 'z', 2)");
    }

    @Test
    void findAllAuthorHydratesAnnotatedBooksAutomatically() {
        List<IntAuthor> result = new ArrayList<>();
        StepVerifier.create(support.operations().findAll(IntAuthor.class, QuerySpec.empty()))
                .recordWith(() -> result)
                .expectNextCount(2)
                .verifyComplete();

        assertEquals(2, result.size());
        IntAuthor first = result.stream().filter(a -> a.getId() == 1L).findFirst().orElseThrow();
        assertNotNull(first.getBooks(), "books는 annotation hydration으로 채워져야 한다");
        assertEquals(2, first.getBooks().size());
        IntAuthor second = result.stream().filter(a -> a.getId() == 2L).findFirst().orElseThrow();
        assertNotNull(second.getBooks());
        assertEquals(1, second.getBooks().size());
        assertEquals(20L, second.getBooks().get(0).getId());
    }

    @Test
    void findByIdAuthorHydratesAnnotatedBooksAutomatically() {
        StepVerifier.create(support.operations().findById(IntAuthor.class, 1L))
                .assertNext(author -> {
                    assertNotNull(author);
                    assertEquals("ada", author.getName());
                    assertNotNull(author.getBooks());
                    assertEquals(2, author.getBooks().size());
                })
                .verifyComplete();
    }

    @Test
    void findByIdHydratesChildrenInOrderByOrder() {
        // @OrderBy("title DESC") — author 1의 책은 title 내림차순(y, x = id 11, 10)으로 와야 한다.
        StepVerifier.create(support.operations().findById(IntAuthor.class, 1L))
                .assertNext(author -> {
                    assertEquals(2, author.getBooks().size());
                    assertEquals(11L, author.getBooks().get(0).getId());
                    assertEquals(10L, author.getBooks().get(1).getId());
                })
                .verifyComplete();
    }

    @Test
    void findByIdBookHydratesAnnotatedAuthorReference() {
        StepVerifier.create(support.operations().findById(IntBook.class, 10L))
                .assertNext(book -> {
                    assertNotNull(book);
                    assertEquals("x", book.getTitle());
                    assertNotNull(book.getAuthor(), "@ManyToOne author는 단건 hydration으로 채워져야 한다");
                    assertEquals(1L, book.getAuthor().getId());
                    assertEquals("ada", book.getAuthor().getName());
                })
                .verifyComplete();
    }

    @Test
    void findByIdBookWithMissingAuthorPopulatesNullReference() {
        // 존재하지 않는 FK를 가진 row를 추가로 삽입한다.
        support.execute("insert into \"int_book\" (\"id\", \"title\", \"author_id\") values (99, 'orphan', 9999)");

        StepVerifier.create(support.operations().findById(IntBook.class, 99L))
                .assertNext(book -> assertNull(book.getAuthor(),
                        "FK가 존재하지 않으면 hydration 결과는 null이어야 한다"))
                .verifyComplete();
    }

    @Test
    void findByIdAuthorWithExplicitFetchGroupDedupesAgainstAnnotation() {
        // 사용자 명시 fetch group이 동일한 (childType, fkColumn) 페어를 선언해도 child IN-query는 한 번만 실행되며
        // hydration 결과는 동일해야 한다.
        FetchGroup<IntAuthor> group = FetchGroup.forParents(IntAuthor.class)
                .with(IntBook.class, "author_id", IntAuthor::getId, IntAuthor::setBooks)
                .build();

        StepVerifier.create(support.operations().findById(IntAuthor.class, 1L, group))
                .assertNext(author -> {
                    assertNotNull(author.getBooks());
                    assertEquals(2, author.getBooks().size());
                })
                .verifyComplete();
    }

    @Entity
    @Table(name = "int_author")
    static class IntAuthor {
        @Id
        private Long id;

        private String name;

        @OneToMany(targetEntity = IntBook.class, mappedBy = "author")
        @OrderBy("title DESC")
        private List<IntBook> books;

        IntAuthor() {
        }

        Long getId() {
            return id;
        }

        String getName() {
            return name;
        }

        List<IntBook> getBooks() {
            return books;
        }

        void setBooks(List<IntBook> books) {
            this.books = books;
        }
    }

    @Entity
    @Table(name = "int_book")
    static class IntBook {
        @Id
        private Long id;

        private String title;

        @ManyToOne(targetEntity = IntAuthor.class)
        @JoinColumn(name = "author_id")
        private IntAuthor author;

        IntBook() {
        }

        Long getId() {
            return id;
        }

        String getTitle() {
            return title;
        }

        IntAuthor getAuthor() {
            return author;
        }
    }
}
