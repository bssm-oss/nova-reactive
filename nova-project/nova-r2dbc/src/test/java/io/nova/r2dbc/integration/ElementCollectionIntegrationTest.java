package io.nova.r2dbc.integration;

import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import io.nova.schema.SchemaInitializer;
import io.nova.schema.SimpleSchemaInitializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code @ElementCollection}(기본 타입 값 컬렉션)이 H2 in-memory R2DBC driver와 end-to-end로 동작하는지
 * 검증한다 — collection table DDL 생성, save 시 full-replace 동기화, 1-hop hydration.
 */
class ElementCollectionIntegrationTest {
    private H2IntegrationTestSupport support;

    @BeforeEach
    void setUp() {
        support = H2IntegrationTestSupport.create();
        SchemaInitializer schema =
                new SimpleSchemaInitializer(support.operations(), support.metadataFactory(), support.dialect());
        // person 테이블과 @ElementCollection collection table(person_tags)을 생성한다.
        schema.create(Person.class).block();
    }

    @Test
    void savesValuesAndHydrates() {
        Person ada = new Person("ada");
        ada.getTags().add("a");
        ada.getTags().add("b");
        Long id = support.operations().save(ada).map(Person::getId).block();

        StepVerifier.create(support.operations().findById(Person.class, id))
                .assertNext(person -> {
                    assertEquals(2, person.getTags().size());
                    assertTrue(person.getTags().containsAll(List.of("a", "b")));
                })
                .verifyComplete();
    }

    @Test
    void reSaveFullReplacesValues() {
        Person ada = new Person("ada");
        ada.getTags().add("a");
        ada.getTags().add("b");
        Long id = support.operations().save(ada).map(Person::getId).block();

        Person loaded = support.operations().findById(Person.class, id).block();
        loaded.getTags().clear();
        loaded.getTags().add("c");
        support.operations().save(loaded).block();

        StepVerifier.create(support.operations().findById(Person.class, id))
                .assertNext(person -> assertEquals(List.of("c"), person.getTags()))
                .verifyComplete();
    }

    @Test
    void emptyCollectionClearsValues() {
        Person ada = new Person("ada");
        ada.getTags().add("a");
        Long id = support.operations().save(ada).map(Person::getId).block();

        Person loaded = support.operations().findById(Person.class, id).block();
        loaded.getTags().clear();
        support.operations().save(loaded).block();

        StepVerifier.create(support.operations().findById(Person.class, id))
                .assertNext(person -> assertEquals(0, person.getTags().size()))
                .verifyComplete();
    }

    @Entity
    @Table(name = "person")
    public static class Person {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;
        private String name;

        @ElementCollection
        private List<String> tags = new ArrayList<>();

        public Person() {
        }

        public Person(String name) {
            this.name = name;
        }

        public Long getId() {
            return id;
        }

        public String getName() {
            return name;
        }

        public List<String> getTags() {
            return tags;
        }
    }
}
