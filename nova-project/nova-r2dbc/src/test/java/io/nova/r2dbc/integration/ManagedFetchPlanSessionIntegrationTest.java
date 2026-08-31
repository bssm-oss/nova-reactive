package io.nova.r2dbc.integration;

import io.nova.fetch.FetchGroup;
import io.nova.graph.EntityGraph;
import io.nova.graph.EntityGraphs;
import io.nova.core.SqlExecutionListener;
import io.nova.schema.SchemaInitializer;
import io.nova.schema.SimpleSchemaInitializer;
import io.nova.sql.SqlStatement;
import jakarta.persistence.ElementCollection;
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
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class ManagedFetchPlanSessionIntegrationTest {
    private H2IntegrationTestSupport support;
    private EntityGraphs graphs;
    private final RecordingListener recorder = new RecordingListener();
    private Long ownerId;
    private Long recordId;
    private Long dogId;

    @BeforeEach
    void setUp() {
        support = H2IntegrationTestSupport.createWithManagedTransactions(recorder);
        SchemaInitializer schema =
                new SimpleSchemaInitializer(support.operations(), support.metadataFactory(), support.dialect());
        schema.create(
                        Owner.class, Record.class, Pet.class, Dog.class, AdHocRecord.class,
                        PartialOwner.class, PartialChild.class, Label.class)
                .block();
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

    @Test
    void adHocFetchGroupManagesTransientTargetBeforeSetterAndFlushesIt() {
        AdHocRecord saved = support.operations().save(new AdHocRecord("ad-hoc", dogId)).block();
        FetchGroup<AdHocRecord> group = FetchGroup.forParents(AdHocRecord.class)
                .withReferencedParent(Pet.class, "id", record -> record.getPetId(), AdHocRecord::setPet)
                .build();

        StepVerifier.create(support.operations().inTransaction(ops ->
                        ops.findById(Dog.class, dogId)
                                .flatMap(preloaded -> ops.findById(AdHocRecord.class, saved.getId(), group)
                                        .map(record -> {
                                            assertSame(preloaded, record.getPet());
                                            ((Dog) record.getPet()).setBark("ad-hoc-bark");
                                            return record;
                                        }))))
                .expectNextCount(1)
                .verifyComplete();

        StepVerifier.create(support.operations().findById(Dog.class, dogId))
                .assertNext(dog -> assertEquals("ad-hoc-bark", dog.getBark()))
                .verifyComplete();
    }

    @Test
    void nestedPartialChildKeepsUnloadedOwningCollectionRowsWithoutDml() {
        Label label = support.operations().save(new Label("retained-label")).block();
        PartialOwner owner = support.operations().save(new PartialOwner("partial-owner")).block();
        Pet dog = support.operations().findById(Dog.class, dogId).block();
        PartialChild child = new PartialChild(owner, dog);
        child.getLabels().add(label);
        child.getTags().add("retained-tag");
        child = support.operations().save(child).block();
        EntityGraph<PartialOwner> graph = graphs.building(PartialOwner.class)
                .addSubgraph("children")
                .addAttributeNodes("pet")
                .build();

        recorder.clear();
        StepVerifier.create(support.operations().inTransaction(ops ->
                        ops.findById(PartialOwner.class, owner.getId(), graph)))
                .expectNextCount(1)
                .verifyComplete();
        assertEquals(0, recorder.dmlCount(), "partially loaded child must not replace untracked collections");

        StepVerifier.create(support.operations().findById(PartialChild.class, child.getId()))
                .assertNext(reloaded -> {
                    assertEquals(1, reloaded.getLabels().size());
                    assertEquals("retained-label", reloaded.getLabels().iterator().next().getName());
                    assertEquals(Set.of("retained-tag"), reloaded.getTags());
                })
                .verifyComplete();
    }

    private static FetchGroup<Record> recordPetFetchGroup() {
        return FetchGroup.forParents(Record.class)
                .withReferencedParent(Pet.class, "id", record -> record.getPet().getId(), Record::setPet)
                .build();
    }

    private static final class RecordingListener implements SqlExecutionListener {
        private final List<String> statements = new CopyOnWriteArrayList<>();

        @Override
        public void onBeforeExecution(SqlStatement statement) {
            statements.add(statement.sql());
        }

        void clear() {
            statements.clear();
        }

        long dmlCount() {
            return statements.stream()
                    .map(sql -> sql.stripLeading().toLowerCase(Locale.ROOT))
                    .filter(sql -> sql.startsWith("insert") || sql.startsWith("update") || sql.startsWith("delete"))
                    .count();
        }
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

    @Entity
    @Table(name = "managed_fetch_ad_hoc_record")
    public static class AdHocRecord {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;
        private String title;
        private Long petId;
        @Transient
        private Pet pet;

        public AdHocRecord() {
        }

        AdHocRecord(String title, Long petId) {
            this.title = title;
            this.petId = petId;
        }

        Long getId() {
            return id;
        }

        Long getPetId() {
            return petId;
        }

        Pet getPet() {
            return pet;
        }

        void setPet(Pet pet) {
            this.pet = pet;
        }
    }

    @Entity
    @Table(name = "managed_fetch_partial_owner")
    public static class PartialOwner {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;
        private String name;
        @OneToMany(targetEntity = PartialChild.class, mappedBy = "owner")
        private List<PartialChild> children;

        public PartialOwner() {
        }

        PartialOwner(String name) {
            this.name = name;
        }

        Long getId() {
            return id;
        }
    }

    @Entity
    @Table(name = "managed_fetch_partial_child")
    public static class PartialChild {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;
        @ManyToOne(targetEntity = PartialOwner.class)
        @JoinColumn(name = "owner_id")
        private PartialOwner owner;
        @ManyToOne(targetEntity = Pet.class)
        @JoinColumn(name = "pet_id")
        private Pet pet;
        @ManyToMany(targetEntity = Label.class)
        @JoinTable(
                name = "managed_fetch_partial_child_labels",
                joinColumns = @JoinColumn(name = "child_id"),
                inverseJoinColumns = @JoinColumn(name = "label_id"))
        private Set<Label> labels;
        @ElementCollection
        private Set<String> tags;

        public PartialChild() {
            labels = new LinkedHashSet<>();
            tags = new LinkedHashSet<>();
        }

        PartialChild(PartialOwner owner, Pet pet) {
            this();
            this.owner = owner;
            this.pet = pet;
        }

        Long getId() {
            return id;
        }

        void setPet(Pet pet) {
            this.pet = pet;
        }

        Set<Label> getLabels() {
            return labels;
        }

        Set<String> getTags() {
            return tags;
        }
    }

    @Entity
    @Table(name = "managed_fetch_label")
    public static class Label {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;
        private String name;

        public Label() {
        }

        Label(String name) {
            this.name = name;
        }

        String getName() {
            return name;
        }
    }
}
