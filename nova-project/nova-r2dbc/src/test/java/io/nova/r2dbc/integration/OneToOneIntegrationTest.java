package io.nova.r2dbc.integration;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
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
 * 양방향 {@code @OneToOne}(owning FK + inverse mappedBy)이 H2 in-memory R2DBC driver와 end-to-end로
 * round-trip 되는지 검증한다. owning 측은 단건 참조 hydration(@ManyToOne 인프라 재사용), inverse 측은
 * 소유 FK 기준 단건 hydration이 동작해야 한다.
 */
class OneToOneIntegrationTest {
    private H2IntegrationTestSupport support;

    @BeforeEach
    void setUp() {
        support = H2IntegrationTestSupport.create();
        SchemaInitializer schema =
                new SimpleSchemaInitializer(support.operations(), support.metadataFactory(), support.dialect());
        schema.create(Person.class, Passport.class).block();
    }

    @Test
    void owningSideHydratesReferencedEntity() {
        Mono<Person> pipeline = support.operations().save(new Passport("X123"))
                .flatMap(passport -> {
                    Person person = new Person("ada");
                    person.setPassport(passport);
                    return support.operations().save(person);
                })
                .flatMap(savedPerson -> support.operations().findById(Person.class, savedPerson.getId()));

        StepVerifier.create(pipeline)
                .assertNext(person -> {
                    assertEquals("ada", person.getName());
                    assertNotNull(person.getPassport(), "owning @OneToOne은 단건 hydration으로 채워져야 한다");
                    assertEquals("X123", person.getPassport().getNumber());
                })
                .verifyComplete();
    }

    @Test
    void inverseSideHydratesOwningEntity() {
        Mono<Passport> pipeline = support.operations().save(new Passport("Y999"))
                .flatMap(passport -> {
                    Person person = new Person("ben");
                    person.setPassport(passport);
                    return support.operations().save(person).thenReturn(passport);
                })
                .flatMap(passport -> support.operations().findById(Passport.class, passport.getId()));

        StepVerifier.create(pipeline)
                .assertNext(passport -> {
                    assertEquals("Y999", passport.getNumber());
                    assertNotNull(passport.getHolder(), "inverse @OneToOne(mappedBy)은 소유 FK로 단건 hydration돼야 한다");
                    assertEquals("ben", passport.getHolder().getName());
                })
                .verifyComplete();
    }

    @Entity
    @Table(name = "person")
    static class Person {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;

        private String name;

        @OneToOne
        @JoinColumn(name = "passport_id")
        private Passport passport;

        Person() {
        }

        Person(String name) {
            this.name = name;
        }

        Long getId() {
            return id;
        }

        String getName() {
            return name;
        }

        Passport getPassport() {
            return passport;
        }

        void setPassport(Passport passport) {
            this.passport = passport;
        }
    }

    @Entity
    @Table(name = "passport")
    static class Passport {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;

        private String number;

        @OneToOne(mappedBy = "passport", targetEntity = Person.class)
        private Person holder;

        Passport() {
        }

        Passport(String number) {
            this.number = number;
        }

        Long getId() {
            return id;
        }

        String getNumber() {
            return number;
        }

        Person getHolder() {
            return holder;
        }
    }
}
