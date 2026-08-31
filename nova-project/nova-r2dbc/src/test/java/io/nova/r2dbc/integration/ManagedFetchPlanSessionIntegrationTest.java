package io.nova.r2dbc.integration;

import io.nova.fetch.FetchGroup;
import io.nova.graph.EntityGraph;
import io.nova.graph.EntityGraphs;
import io.nova.schema.SchemaInitializer;
import io.nova.schema.SimpleSchemaInitializer;
import jakarta.persistence.DiscriminatorColumn;
import jakarta.persistence.DiscriminatorType;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Inheritance;
import jakarta.persistence.InheritanceType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class ManagedFetchPlanSessionIntegrationTest {
    private H2IntegrationTestSupport support;
    private EntityGraphs graphs;
    private Long ownerId;
    private Long recordId;
    private Long dogId;

    @BeforeEach
    void setUp() {
        support = H2IntegrationTestSupport.createWithManagedTransactions();
        SchemaInitializer schema =
                new SimpleSchemaInitializer(support.operations(), support.metadataFactory(), support.dialect());
        schema.create(Owner.class, Record.class, Pet.class, Dog.class).block();
        graphs = new EntityGraphs(support.metadataFactory());

        Dog dog = support.operations().save(new Dog("fido", "quiet")).block();
        Owner owner = support.operations().save(new Owner("before-owner")).block();
        Record record = support.operations().save(new Record("before-record", owner, dog)).block();
        ownerId = owner.getId();
        recordId = record.getId();
        dogId = dog.getId();
    }

    @Test
    void fetchGroupFindByIdAndFindAllShareCanonicalRootAndSubtypeRelation() {
        FetchGroup<Record> group = recordPetFetchGroup();

        StepVerifier.create(support.operations().inTransaction(ops ->
                        ops.findById(Dog.class, dogId)
                                .then(ops.findById(Record.class, recordId, group))
                                .flatMap(first -> {
                                    first.setTitle("fetch-record");
                                    ((Dog) first.getPet()).setBark("fetch-bark");
                                    return ops.findAll(Record.class, group)
                                            .single()
                                            .map(second -> {
                                                assertSame(first, second);
                                                assertSame(first.getPet(), second.getPet());
                                                return second;
                                            });
                                })))
                .expectNextCount(1)
                .verifyComplete();

        StepVerifier.create(support.operations().findById(Record.class, recordId))
                .assertNext(record -> assertEquals("fetch-record", record.getTitle()))
                .verifyComplete();
        StepVerifier.create(support.operations().findById(Dog.class, dogId))
                .assertNext(dog -> assertEquals("fetch-bark", dog.getBark()))
                .verifyComplete();
    }

    @Test
    void nestedEntityGraphFindAllThenFindByIdKeepsCanonicalGraphAndFlushes() {
        EntityGraph<Owner> graph = graphs.building(Owner.class)
                .addSubgraph("records")
                .addAttributeNodes("pet")
                .build();

        StepVerifier.create(support.operations().inTransaction(ops ->
                        ops.findById(Dog.class, dogId)
                                .then(ops.findAll(Owner.class, graph).single())
                                .flatMap(first -> {
                                    Record record = first.getRecords().get(0);
                                    first.setName("graph-owner");
                                    record.setTitle("graph-record");
                                    ((Dog) record.getPet()).setBark("graph-bark");
                                    return ops.findById(Owner.class, ownerId, graph)
                                            .map(second -> {
                                                assertSame(first, second);
                                                assertSame(record, second.getRecords().get(0));
                                                assertSame(record.getPet(), second.getRecords().get(0).getPet());
                                                return second;
                                            });
                                })))
                .expectNextCount(1)
                .verifyComplete();

        StepVerifier.create(support.operations().findById(Owner.class, ownerId))
                .assertNext(owner -> assertEquals("graph-owner", owner.getName()))
                .verifyComplete();
        StepVerifier.create(support.operations().findById(Record.class, recordId))
                .assertNext(record -> assertEquals("graph-record", record.getTitle()))
                .verifyComplete();
        StepVerifier.create(support.operations().findById(Dog.class, dogId))
                .assertNext(dog -> assertEquals("graph-bark", dog.getBark()))
                .verifyComplete();
    }

    private static FetchGroup<Record> recordPetFetchGroup() {
        return FetchGroup.forParents(Record.class)
                .withReferencedParent(Pet.class, "id", record -> record.getPet().getId(), Record::setPet)
                .build();
    }

    @Entity
    @Table(name = "managed_fetch_owner")
    public static class Owner {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;
        private String name;
        @OneToMany(targetEntity = Record.class, mappedBy = "owner")
        private List<Record> records;

        public Owner() {
        }

        Owner(String name) {
            this.name = name;
        }

        Long getId() {
            return id;
        }

        String getName() {
            return name;
        }

        void setName(String name) {
            this.name = name;
        }

        List<Record> getRecords() {
            return records;
        }
    }

    @Entity
    @Table(name = "managed_fetch_record")
    public static class Record {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;
        private String title;
        @ManyToOne(targetEntity = Owner.class)
        @JoinColumn(name = "owner_id")
        private Owner owner;
        @ManyToOne(targetEntity = Pet.class)
        @JoinColumn(name = "pet_id")
        private Pet pet;

        public Record() {
        }

        Record(String title, Owner owner, Pet pet) {
            this.title = title;
            this.owner = owner;
            this.pet = pet;
        }

        Long getId() {
            return id;
        }

        String getTitle() {
            return title;
        }

        void setTitle(String title) {
            this.title = title;
        }

        Pet getPet() {
            return pet;
        }

        void setPet(Pet pet) {
            this.pet = pet;
        }
    }

    @Entity
    @Table(name = "managed_fetch_pet")
    @Inheritance(strategy = InheritanceType.SINGLE_TABLE)
    @DiscriminatorColumn(name = "kind", discriminatorType = DiscriminatorType.STRING)
    public static class Pet {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;
        private String name;

        public Pet() {
        }

        Pet(String name) {
            this.name = name;
        }

        Long getId() {
            return id;
        }
    }

    @Entity
    @DiscriminatorValue("DOG")
    public static class Dog extends Pet {
        private String bark;

        public Dog() {
        }

        Dog(String name, String bark) {
            super(name);
            this.bark = bark;
        }

        String getBark() {
            return bark;
        }

        void setBark(String bark) {
            this.bark = bark;
        }
    }
}
