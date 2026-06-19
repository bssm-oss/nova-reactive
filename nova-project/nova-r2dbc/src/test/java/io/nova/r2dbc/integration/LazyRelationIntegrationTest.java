package io.nova.r2dbc.integration;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import io.nova.schema.SchemaInitializer;
import io.nova.schema.SimpleSchemaInitializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * {@code @ManyToOne(fetch=LAZY)} / {@code @OneToOne(fetch=LAZY)} 엔티티가 H2 in-memory R2DBC driver와
 * end-to-end로 round-trip 되는지 검증한다.
 *
 * <p>Nova는 lazy proxy가 없어 EAGER/LAZY가 런타임에서 동일하게 동작한다(관계는 FetchGroup으로만 populate,
 * FK 컬럼은 정상 persist). 따라서 LAZY로 선언해도 FK는 EAGER와 동일하게 저장되고, 단건 참조 hydration도
 * 동일하게 동작해야 한다. SQL string unit test만으로는 driver column binding 호환을 보장할 수 없어 별도
 * integration test로 보호한다(feedback_integration_test_surfaces_bugs.md).
 */
class LazyRelationIntegrationTest {
    private H2IntegrationTestSupport support;

    @BeforeEach
    void setUp() {
        support = H2IntegrationTestSupport.create();
        SchemaInitializer schema =
                new SimpleSchemaInitializer(support.operations(), support.metadataFactory(), support.dialect());
        schema.create(LazyAuthor.class, LazyBook.class, LazyPassport.class, LazyPerson.class).block();
    }

    @Test
    void lazyManyToOnePersistsForeignKeyAndHydratesReference() {
        Mono<LazyBook> pipeline = support.operations().save(new LazyAuthor("ada"))
                .flatMap(author -> {
                    LazyBook book = new LazyBook("clean code");
                    book.setAuthor(author);
                    return support.operations().save(book);
                })
                .flatMap(saved -> support.operations().findById(LazyBook.class, saved.getId()));

        StepVerifier.create(pipeline)
                .assertNext(book -> {
                    assertEquals("clean code", book.getTitle());
                    // FK가 persist됐다면 단건 참조 hydration이 author를 채운다(EAGER와 동일 동작).
                    assertNotNull(book.getAuthor(), "@ManyToOne(fetch=LAZY) FK는 persist되고 참조는 hydration돼야 한다");
                    assertEquals("ada", book.getAuthor().getName());
                })
                .verifyComplete();
    }

    @Test
    void lazyOneToOnePersistsForeignKeyAndHydratesReference() {
        Mono<LazyPerson> pipeline = support.operations().save(new LazyPassport("X123"))
                .flatMap(passport -> {
                    LazyPerson person = new LazyPerson("ben");
                    person.setPassport(passport);
                    return support.operations().save(person);
                })
                .flatMap(saved -> support.operations().findById(LazyPerson.class, saved.getId()));

        StepVerifier.create(pipeline)
                .assertNext(person -> {
                    assertEquals("ben", person.getName());
                    assertNotNull(person.getPassport(), "@OneToOne(fetch=LAZY) FK는 persist되고 참조는 hydration돼야 한다");
                    assertEquals("X123", person.getPassport().getNumber());
                })
                .verifyComplete();
    }

    // --- fixtures -----------------------------------------------------------

    @Entity
    @Table(name = "lazy_author")
    static class LazyAuthor {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;

        private String name;

        LazyAuthor() {
        }

        LazyAuthor(String name) {
            this.name = name;
        }

        Long getId() {
            return id;
        }

        String getName() {
            return name;
        }
    }

    @Entity
    @Table(name = "lazy_book")
    static class LazyBook {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;

        private String title;

        @ManyToOne(targetEntity = LazyAuthor.class, fetch = FetchType.LAZY)
        @JoinColumn(name = "author_id")
        private LazyAuthor author;

        LazyBook() {
        }

        LazyBook(String title) {
            this.title = title;
        }

        Long getId() {
            return id;
        }

        String getTitle() {
            return title;
        }

        LazyAuthor getAuthor() {
            return author;
        }

        void setAuthor(LazyAuthor author) {
            this.author = author;
        }
    }

    @Entity
    @Table(name = "lazy_passport")
    static class LazyPassport {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;

        private String number;

        LazyPassport() {
        }

        LazyPassport(String number) {
            this.number = number;
        }

        Long getId() {
            return id;
        }

        String getNumber() {
            return number;
        }
    }

    @Entity
    @Table(name = "lazy_person")
    static class LazyPerson {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;

        private String name;

        @OneToOne(targetEntity = LazyPassport.class, fetch = FetchType.LAZY)
        @JoinColumn(name = "passport_id")
        private LazyPassport passport;

        LazyPerson() {
        }

        LazyPerson(String name) {
            this.name = name;
        }

        Long getId() {
            return id;
        }

        String getName() {
            return name;
        }

        LazyPassport getPassport() {
            return passport;
        }

        void setPassport(LazyPassport passport) {
            this.passport = passport;
        }
    }
}
