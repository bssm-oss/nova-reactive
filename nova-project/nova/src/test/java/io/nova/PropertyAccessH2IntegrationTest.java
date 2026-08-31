package io.nova;

import jakarta.persistence.Access;
import jakarta.persistence.AccessType;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Convert;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Embedded;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinColumns;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapKeyColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Temporal;
import jakarta.persistence.TemporalType;
import io.nova.core.ReactiveEntityOperations;
import io.nova.annotation.Json;
import io.nova.json.JsonCodec;
import io.nova.schema.SchemaInitializer;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.Date;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.io.Serializable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code @Access(AccessType.PROPERTY)} 엔티티가 실제 r2dbc-h2 driver 위에서 save/findById full
 * round-trip 되는지 검증한다 — 상태 read/write가 field가 아니라 JavaBean getter/setter를 경유하는지까지.
 *
 * <p>엔티티는 private 필드를 직접 노출하지 않고(state는 getter/setter로만 도달 가능) sentinel 필드로
 * getter/setter 호출 여부를 기록해, Nova의 binding/hydration이 PROPERTY access 경로를 탔는지 고정한다.
 */
class PropertyAccessH2IntegrationTest {

    private static final AtomicInteger DB_SEQ = new AtomicInteger();

    private ConnectionFactory freshConnectionFactory() {
        int seq = DB_SEQ.incrementAndGet();
        return ConnectionFactories.get(
                "r2dbc:h2:mem:///propaccess" + seq + "?options=DB_CLOSE_DELAY=-1");
    }

    @Test
    void classLevelPropertyAccessEntitySavesAndLoadsViaAccessors() {
        ConnectionFactory cf = freshConnectionFactory();
        SchemaInitializer schema = Nova.schemaInitializer(cf);
        ReactiveEntityOperations operations = Nova.create(cf);

        PropertyAccessAccount toSave = new PropertyAccessAccount("alice@example.com");

        StepVerifier.create(
                schema.create(PropertyAccessAccount.class)
                        .then(operations.save(toSave))
                        .flatMap(saved -> {
                            // IDENTITY id는 INSERT 후 setter로 다시 주입된다.
                            assertNotNull(saved.getId(), "save 후 IDENTITY id가 채워져 있어야 한다");
                            return operations.findById(PropertyAccessAccount.class, saved.getId());
                        })
        ).assertNext(loaded -> {
            assertNotNull(loaded);
            assertNotNull(loaded.getId());
            assertEquals("alice@example.com", loaded.getEmail());
            // findById의 row hydration이 setter를 거쳐 상태를 채웠어야 한다.
            assertTrue(loaded.emailSetterInvoked, "findById hydration은 setter를 경유해야 한다");
        }).verifyComplete();
    }

    @Test
    void memberLevelPropertyOverrideRoundTrips() {
        ConnectionFactory cf = freshConnectionFactory();
        SchemaInitializer schema = Nova.schemaInitializer(cf);
        ReactiveEntityOperations operations = Nova.create(cf);

        MixedAccessAccount toSave = new MixedAccessAccount("field-val", "prop-val");

        StepVerifier.create(
                schema.create(MixedAccessAccount.class)
                        .then(operations.save(toSave))
                        .flatMap(saved -> {
                            assertNotNull(saved.getId());
                            return operations.findById(MixedAccessAccount.class, saved.getId());
                        })
        ).assertNext(loaded -> {
            assertNotNull(loaded.getId());
            assertEquals("field-val", loaded.fieldMappedDirect());
            assertEquals("prop-val", loaded.getPropertyMapped());
            assertTrue(loaded.propertySetterInvoked,
                    "PROPERTY override 컬럼은 setter를 경유해 채워져야 한다");
        }).verifyComplete();
    }

    @Test
    void classLevelPropertyAccessManyToOneRelationRoundTripsViaAccessors() {
        ConnectionFactory cf = freshConnectionFactory();
        SchemaInitializer schema = Nova.schemaInitializer(cf);
        ReactiveEntityOperations operations = Nova.create(cf);

        Blog blog = new Blog("nova-blog");
        PropertyAccessArticle article = new PropertyAccessArticle("hello");

        StepVerifier.create(
                schema.create(Blog.class)
                        .then(schema.create(PropertyAccessArticle.class))
                        .then(operations.save(blog))
                        .flatMap(savedBlog -> {
                            assertNotNull(savedBlog.getId());
                            article.setBlog(savedBlog);
                            return operations.save(article);
                        })
                        .flatMap(savedArticle -> {
                            // owner INSERT는 FK 값을 관계 getter로 읽어 바인딩해야 한다(field 직접접근 아님).
                            assertTrue(article.blogGetterInvoked,
                                    "save의 FK 바인딩은 관계 getter를 경유해야 한다");
                            return operations.findById(PropertyAccessArticle.class, savedArticle.getId());
                        })
        ).assertNext(loaded -> {
            assertNotNull(loaded.getId());
            assertEquals("hello", loaded.getTitle());
            assertNotNull(loaded.getBlog(), "@ManyToOne는 findById에서 hydrate되어야 한다");
            assertEquals(blog.getId(), loaded.getBlog().getId());
            // row 디코딩/hydration이 관계 setter를 경유해야 한다(field 직접접근 아님).
            assertTrue(loaded.blogSetterInvoked, "관계 hydration은 setter를 경유해야 한다");
        }).verifyComplete();
    }

    @Test
    void getterIdSelectsImplicitPropertyAccessAndGeneratedIdUsesSetter() {
        ConnectionFactory cf = freshConnectionFactory();
        SchemaInitializer schema = Nova.schemaInitializer(cf);
        ReactiveEntityOperations operations = Nova.create(cf);
        ImplicitPropertyAccount account = new ImplicitPropertyAccount("visible-name");

        StepVerifier.create(schema.create(ImplicitPropertyAccount.class)
                .then(operations.save(account))
                .flatMap(saved -> {
                    assertNotNull(saved.getId());
                    assertTrue(saved.idSetterInvoked,
                            "generated IDENTITY value must be written through the property setter");
                    return operations.findById(ImplicitPropertyAccount.class, saved.getId());
                }))
                .assertNext(loaded -> {
                    assertEquals("visible-name", loaded.getDisplayName());
                    assertTrue(loaded.displayNameSetterInvoked,
                            "getter-only logical column must hydrate through setDisplayName");
                })
                .verifyComplete();
    }

    @Test
    void propertyGetterConvertersAndNovaJsonRoundTripThroughSetters() {
        ConnectionFactory cf = freshConnectionFactory();
        SchemaInitializer schema = Nova.schemaInitializer(cf);
        ReactiveEntityOperations operations = Nova.create(cf, Nova.resolveDialect(cf), new PrefixJsonCodec());
        PropertyValueAccount account = new PropertyValueAccount(
                new Code("a7"), State.ACTIVE, new Date(1_700_000_000_000L), "metadata");

        StepVerifier.create(schema.create(PropertyValueAccount.class)
                .then(operations.save(account))
                .flatMap(saved -> operations.findById(PropertyValueAccount.class, saved.getId())))
                .assertNext(loaded -> {
                    assertEquals(new Code("a7"), loaded.getCode());
                    assertEquals(State.ACTIVE, loaded.getState());
                    assertEquals(new Date(1_700_000_000_000L), loaded.getCreatedOn());
                    assertEquals("metadata", loaded.getMetadata());
                    assertTrue(loaded.allValueSettersInvoked,
                            "PROPERTY conversion hydration must invoke every logical setter");
                })
                .verifyComplete();
    }

    @Test
    void propertyOneToManyInfersListTargetAndHydratesThroughSetterAfterFlush() {
        ConnectionFactory cf = freshConnectionFactory();
        SchemaInitializer schema = Nova.schemaInitializer(cf);
        ReactiveEntityOperations operations = Nova.create(cf);
        PropertyParent parent = new PropertyParent("parent");
        parent.addChild(new PropertyChild("first"));
        parent.addChild(new PropertyChild("second"));

        StepVerifier.create(schema.create(List.of(PropertyParent.class, PropertyChild.class))
                .then(Nova.entityManager(cf).inTransaction(manager -> manager.persist(parent)
                        .flatMap(saved -> manager.flush().thenReturn(saved.getId()))))
                .flatMap(id -> operations.findById(PropertyParent.class, id)))
                .assertNext(loaded -> {
                    assertEquals(List.of("first", "second"),
                            loaded.getChildren().stream().map(PropertyChild::getName).toList());
                    assertTrue(loaded.childrenSetterInvoked,
                            "inverse collection hydration must use the PROPERTY setter");
                })
                .verifyComplete();
    }

    @Test
    void propertyEmbeddedMutableValueRoundTripsThroughHostSetter() {
        ConnectionFactory cf = freshConnectionFactory();
        SchemaInitializer schema = Nova.schemaInitializer(cf);
        ReactiveEntityOperations operations = Nova.create(cf);
        PropertyEmbeddedOwner owner = new PropertyEmbeddedOwner(new MutableAddress("Seoul", "06164"));

        StepVerifier.create(schema.create(PropertyEmbeddedOwner.class)
                .then(operations.save(owner))
                .flatMap(saved -> operations.findById(PropertyEmbeddedOwner.class, saved.getId())))
                .assertNext(loaded -> {
                    assertEquals("Seoul", loaded.getAddress().getCity());
                    assertEquals("06164", loaded.getAddress().getPostalCode());
                    assertTrue(loaded.addressSetterInvoked,
                            "mutable @Embedded PROPERTY value must hydrate through its host setter");
                })
                .verifyComplete();
    }

    @Test
    void propertyEmbeddedIdRecordAndPropertyIdClassRoundTrip() {
        ConnectionFactory cf = freshConnectionFactory();
        SchemaInitializer schema = Nova.schemaInitializer(cf);
        ReactiveEntityOperations operations = Nova.create(cf);
        PropertyRecordKeyEntity record = new PropertyRecordKeyEntity(new PropertyRecordKey("kr", 7L));
        PropertyIdClassEntity idClass = new PropertyIdClassEntity("us", 8L, "id-class");

        StepVerifier.create(schema.create(List.of(PropertyRecordKeyEntity.class, PropertyIdClassEntity.class))
                .then(operations.save(record))
                .then(operations.save(idClass))
                .then(operations.findById(PropertyRecordKeyEntity.class, record.getId()))
                .zipWith(operations.findById(PropertyIdClassEntity.class, new PropertyIdClassKey("us", 8L))))
                .assertNext(pair -> {
                    assertEquals(new PropertyRecordKey("kr", 7L), pair.getT1().getId());
                    assertTrue(pair.getT1().idSetterInvoked);
                    assertEquals("id-class", pair.getT2().getLabel());
                    assertTrue(pair.getT2().countrySetterInvoked && pair.getT2().numberSetterInvoked);
                })
                .verifyComplete();
    }

    @Test
    void getterOnlyManyToManyAndElementCollectionsRoundTripThroughSetters() {
        ConnectionFactory cf = freshConnectionFactory();
        SchemaInitializer schema = Nova.schemaInitializer(cf);
        ReactiveEntityOperations operations = Nova.create(cf);
        PropertyCollectionTag tag = new PropertyCollectionTag("java");
        PropertyCollectionOwner owner = new PropertyCollectionOwner();
        owner.getTags().add(tag);
        owner.getLabels().add("reactive");
        owner.getWeights().put("priority", 1);

        StepVerifier.create(schema.create(List.of(PropertyCollectionOwner.class, PropertyCollectionTag.class))
                .then(operations.save(tag))
                .then(operations.save(owner))
                .flatMap(saved -> operations.findById(PropertyCollectionOwner.class, saved.getId())))
                .assertNext(loaded -> {
                    assertEquals(List.of("java"), loaded.getTags().stream().map(PropertyCollectionTag::getName).toList());
                    assertEquals(List.of("reactive"), loaded.getLabels());
                    assertEquals(Map.of("priority", 1), loaded.getWeights());
                    assertTrue(loaded.tagsSetterInvoked && loaded.labelsSetterInvoked && loaded.weightsSetterInvoked,
                            "getter-only collection properties must hydrate through their setters");
                })
                .verifyComplete();
    }

    @Test
    void propertyJoinColumnsReferenceConvertedCompositeTargetId() {
        ConnectionFactory cf = freshConnectionFactory();
        SchemaInitializer schema = Nova.schemaInitializer(cf);
        ReactiveEntityOperations operations = Nova.create(cf);
        PropertyCompositeTarget target = new PropertyCompositeTarget(new Code("acme"), 9L);
        PropertyCompositeRef ref = new PropertyCompositeRef(target);

        StepVerifier.create(schema.create(List.of(PropertyCompositeTarget.class, PropertyCompositeRef.class))
                .then(operations.save(target))
                .then(operations.save(ref))
                .flatMap(saved -> operations.findById(PropertyCompositeRef.class, saved.getId())))
                .assertNext(loaded -> {
                    assertNotNull(loaded.getTarget(), "getter @JoinColumns FK must hydrate a target stub");
                    assertEquals(new Code("acme"), loaded.getTarget().getCode());
                    assertEquals(9L, loaded.getTarget().getSequence());
                    assertTrue(loaded.targetSetterInvoked);
                })
                .verifyComplete();
    }

    @Entity
    @Table(name = "property_access_accounts")
    @Access(AccessType.PROPERTY)
    public static class PropertyAccessAccount {
        private Long id;

        private String email;

        public transient boolean emailSetterInvoked;

        public PropertyAccessAccount() {
        }

        public PropertyAccessAccount(String email) {
            this.email = email;
        }

        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        @Column(name = "email")
        public String getEmail() {
            return email;
        }

        public void setEmail(String email) {
            this.emailSetterInvoked = true;
            this.email = email;
        }
    }

    @Entity
    @Table(name = "mixed_access_accounts")
    public static class MixedAccessAccount {
        private Long id;

        // 멤버 레벨 override → FIELD 접근.
        @Access(AccessType.FIELD)
        @Column(name = "field_mapped")
        private String fieldMapped;

        // 멤버 레벨 override → PROPERTY 접근.
        private String propertyMapped;

        public transient boolean propertySetterInvoked;

        public MixedAccessAccount() {
        }

        public MixedAccessAccount(String fieldMapped, String propertyMapped) {
            this.fieldMapped = fieldMapped;
            this.propertyMapped = propertyMapped;
        }

        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        // FIELD-access 컬럼은 getter/setter가 없어도 동작해야 한다(테스트 검증용 reader만 둔다).
        String fieldMappedDirect() {
            return fieldMapped;
        }

        @Access(AccessType.PROPERTY)
        @Column(name = "property_mapped")
        public String getPropertyMapped() {
            return propertyMapped;
        }

        public void setPropertyMapped(String propertyMapped) {
            this.propertySetterInvoked = true;
            this.propertyMapped = propertyMapped;
        }
    }

    // --- relation @Access(PROPERTY) fixtures --------------------------------

    @Entity
    @Table(name = "property_access_blogs")
    public static class Blog {
        private Long id;

        private String name;

        public Blog() {
        }

        public Blog(String name) {
            this.name = name;
        }

        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        @Column(name = "name")
        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }

    @Entity
    @Table(name = "property_access_articles")
    @Access(AccessType.PROPERTY)
    public static class PropertyAccessArticle {
        private Long id;

        private String title;

        private Blog blog;

        public transient boolean blogGetterInvoked;
        public transient boolean blogSetterInvoked;

        public PropertyAccessArticle() {
        }

        public PropertyAccessArticle(String title) {
            this.title = title;
        }

        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        @Column(name = "title")
        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }

        @ManyToOne
        @JoinColumn(name = "blog_id")
        public Blog getBlog() {
            this.blogGetterInvoked = true;
            return blog;
        }

        public void setBlog(Blog blog) {
            this.blogSetterInvoked = true;
            this.blog = blog;
        }
    }

    @Entity
    @Table(name = "implicit_property_accounts")
    public static class ImplicitPropertyAccount {
        private Long generated;
        private String storage;
        boolean idSetterInvoked;
        boolean displayNameSetterInvoked;

        public ImplicitPropertyAccount() {
        }

        ImplicitPropertyAccount(String displayName) {
            this.storage = displayName;
        }

        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        public Long getId() {
            return generated;
        }

        public void setId(Long id) {
            idSetterInvoked = true;
            generated = id;
        }

        @Column(name = "display_name")
        public String getDisplayName() {
            return storage;
        }

        public void setDisplayName(String displayName) {
            displayNameSetterInvoked = true;
            storage = displayName;
        }
    }

    @Entity
    @Table(name = "property_value_accounts")
    @Access(AccessType.PROPERTY)
    public static class PropertyValueAccount {
        private Long key;
        private Code code;
        private State state;
        private Date createdOn;
        private String metadata;
        boolean codeSetterInvoked;
        boolean stateSetterInvoked;
        boolean createdOnSetterInvoked;
        boolean metadataSetterInvoked;
        boolean allValueSettersInvoked;

        public PropertyValueAccount() {
        }

        PropertyValueAccount(Code code, State state, Date createdOn, String metadata) {
            this.code = code;
            this.state = state;
            this.createdOn = createdOn;
            this.metadata = metadata;
        }

        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        public Long getId() {
            return key;
        }

        public void setId(Long id) {
            key = id;
        }

        @Convert(converter = CodeConverter.class)
        public Code getCode() {
            return code;
        }

        public void setCode(Code code) {
            codeSetterInvoked = true;
            this.code = code;
            updateSetterState();
        }

        @Enumerated(EnumType.STRING)
        public State getState() {
            return state;
        }

        public void setState(State state) {
            stateSetterInvoked = true;
            this.state = state;
            updateSetterState();
        }

        @Temporal(TemporalType.TIMESTAMP)
        public Date getCreatedOn() {
            return createdOn;
        }

        public void setCreatedOn(Date createdOn) {
            createdOnSetterInvoked = true;
            this.createdOn = createdOn;
            updateSetterState();
        }

        @Json
        public String getMetadata() {
            return metadata;
        }

        public void setMetadata(String metadata) {
            metadataSetterInvoked = true;
            this.metadata = metadata;
            updateSetterState();
        }

        private void updateSetterState() {
            allValueSettersInvoked = codeSetterInvoked && stateSetterInvoked
                    && createdOnSetterInvoked && metadataSetterInvoked;
        }
    }

    enum State {
        ACTIVE
    }

    record Code(String value) {
    }

    public static class CodeConverter implements jakarta.persistence.AttributeConverter<Code, String> {
        @Override
        public String convertToDatabaseColumn(Code attribute) {
            return attribute == null ? null : attribute.value();
        }

        @Override
        public Code convertToEntityAttribute(String dbData) {
            return dbData == null ? null : new Code(dbData);
        }
    }

    private static final class PrefixJsonCodec implements JsonCodec {
        @Override
        public String encode(Object value) {
            return "json:" + value;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T decode(String json, Class<T> type) {
            String normalized = json;
            if (normalized.length() >= 2 && normalized.startsWith("\"") && normalized.endsWith("\"")) {
                normalized = normalized.substring(1, normalized.length() - 1);
            }
            return (T) normalized.substring("json:".length());
        }
    }

    @Entity
    @Table(name = "property_parents")
    @Access(AccessType.PROPERTY)
    public static class PropertyParent {
        private Long id;
        private String name;
        private List<PropertyChild> children = new ArrayList<>();
        boolean childrenSetterInvoked;

        public PropertyParent() {
        }

        PropertyParent(String name) {
            this.name = name;
        }

        void addChild(PropertyChild child) {
            children.add(child);
            child.setParent(this);
        }

        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        @OneToMany(mappedBy = "parent", cascade = CascadeType.ALL, orphanRemoval = true)
        @OrderColumn(name = "child_order")
        public List<PropertyChild> getChildren() {
            return children;
        }

        public void setChildren(List<PropertyChild> children) {
            childrenSetterInvoked = true;
            this.children = children;
        }
    }

    @Entity
    @Table(name = "property_children")
    @Access(AccessType.PROPERTY)
    public static class PropertyChild {
        private Long id;
        private String name;
        private PropertyParent parent;

        public PropertyChild() {
        }

        PropertyChild(String name) {
            this.name = name;
        }

        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        @ManyToOne
        @JoinColumn(name = "parent_id")
        public PropertyParent getParent() {
            return parent;
        }

        public void setParent(PropertyParent parent) {
            this.parent = parent;
        }
    }

    @Embeddable
    @Access(AccessType.PROPERTY)
    public static class MutableAddress {
        private String city;
        private String postalCode;

        public MutableAddress() {
        }

        MutableAddress(String city, String postalCode) {
            this.city = city;
            this.postalCode = postalCode;
        }

        public String getCity() {
            return city;
        }

        public void setCity(String city) {
            this.city = city;
        }

        public String getPostalCode() {
            return postalCode;
        }

        public void setPostalCode(String postalCode) {
            this.postalCode = postalCode;
        }
    }

    @Entity
    @Table(name = "property_embedded_owners")
    @Access(AccessType.PROPERTY)
    public static class PropertyEmbeddedOwner {
        private Long id;
        private MutableAddress address;
        boolean addressSetterInvoked;

        public PropertyEmbeddedOwner() {
        }

        PropertyEmbeddedOwner(MutableAddress address) {
            this.address = address;
        }

        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        @Embedded
        public MutableAddress getAddress() {
            return address;
        }

        public void setAddress(MutableAddress address) {
            addressSetterInvoked = true;
            this.address = address;
        }
    }

    @Embeddable
    public record PropertyRecordKey(String country, Long number) {
    }

    @Entity
    @Table(name = "property_record_keys")
    @Access(AccessType.PROPERTY)
    public static class PropertyRecordKeyEntity {
        private PropertyRecordKey id;
        boolean idSetterInvoked;

        public PropertyRecordKeyEntity() {
        }

        PropertyRecordKeyEntity(PropertyRecordKey id) {
            this.id = id;
        }

        @EmbeddedId
        public PropertyRecordKey getId() {
            return id;
        }

        public void setId(PropertyRecordKey id) {
            idSetterInvoked = true;
            this.id = id;
        }
    }

    public static class PropertyIdClassKey implements Serializable {
        private String country;
        private Long number;

        public PropertyIdClassKey() {
        }

        PropertyIdClassKey(String country, Long number) {
            this.country = country;
            this.number = number;
        }
    }

    @Entity
    @Table(name = "property_id_class_keys")
    @IdClass(PropertyIdClassKey.class)
    @Access(AccessType.PROPERTY)
    public static class PropertyIdClassEntity {
        private String country;
        private Long number;
        private String label;
        boolean countrySetterInvoked;
        boolean numberSetterInvoked;

        public PropertyIdClassEntity() {
        }

        PropertyIdClassEntity(String country, Long number, String label) {
            this.country = country;
            this.number = number;
            this.label = label;
        }

        @Id
        public String getCountry() {
            return country;
        }

        public void setCountry(String country) {
            countrySetterInvoked = true;
            this.country = country;
        }

        @Id
        public Long getNumber() {
            return number;
        }

        public void setNumber(Long number) {
            numberSetterInvoked = true;
            this.number = number;
        }

        public String getLabel() {
            return label;
        }

        public void setLabel(String label) {
            this.label = label;
        }
    }

    @Entity
    @Table(name = "property_collection_tags")
    @Access(AccessType.PROPERTY)
    public static class PropertyCollectionTag {
        private Long id;
        private String name;

        public PropertyCollectionTag() {
        }

        PropertyCollectionTag(String name) {
            this.name = name;
        }

        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }

    @Entity
    @Table(name = "property_collection_owners")
    @Access(AccessType.PROPERTY)
    public static class PropertyCollectionOwner {
        private Long id;
        private List<PropertyCollectionTag> tags = new ArrayList<>();
        private List<String> labels = new ArrayList<>();
        private Map<String, Integer> weights = new LinkedHashMap<>();
        boolean tagsSetterInvoked;
        boolean labelsSetterInvoked;
        boolean weightsSetterInvoked;

        public PropertyCollectionOwner() {
        }

        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        @ManyToMany
        @JoinTable(name = "property_owner_tags",
                joinColumns = @JoinColumn(name = "owner_id"),
                inverseJoinColumns = @JoinColumn(name = "tag_id"))
        public List<PropertyCollectionTag> getTags() {
            return tags;
        }

        public void setTags(List<PropertyCollectionTag> tags) {
            tagsSetterInvoked = true;
            this.tags = tags;
        }

        @ElementCollection
        @CollectionTable(name = "property_owner_labels", joinColumns = @JoinColumn(name = "owner_id"))
        @OrderColumn(name = "label_order")
        public List<String> getLabels() {
            return labels;
        }

        public void setLabels(List<String> labels) {
            labelsSetterInvoked = true;
            this.labels = labels;
        }

        @ElementCollection
        @CollectionTable(name = "property_owner_weights", joinColumns = @JoinColumn(name = "owner_id"))
        @MapKeyColumn(name = "weight_name")
        @Column(name = "weight_value")
        public Map<String, Integer> getWeights() {
            return weights;
        }

        public void setWeights(Map<String, Integer> weights) {
            weightsSetterInvoked = true;
            this.weights = weights;
        }
    }

    public static class PropertyCompositeTargetKey implements Serializable {
        private Code code;
        private Long sequence;

        public PropertyCompositeTargetKey() {
        }

        PropertyCompositeTargetKey(Code code, Long sequence) {
            this.code = code;
            this.sequence = sequence;
        }
    }

    @Entity
    @Table(name = "property_composite_targets")
    @IdClass(PropertyCompositeTargetKey.class)
    @Access(AccessType.PROPERTY)
    public static class PropertyCompositeTarget {
        private Code code;
        private Long sequence;

        public PropertyCompositeTarget() {
        }

        PropertyCompositeTarget(Code code, Long sequence) {
            this.code = code;
            this.sequence = sequence;
        }

        @Id
        @Convert(converter = CodeConverter.class)
        @Column(name = "target_code")
        public Code getCode() {
            return code;
        }

        public void setCode(Code code) {
            this.code = code;
        }

        @Id
        @Column(name = "target_sequence")
        public Long getSequence() {
            return sequence;
        }

        public void setSequence(Long sequence) {
            this.sequence = sequence;
        }
    }

    @Entity
    @Table(name = "property_composite_refs")
    @Access(AccessType.PROPERTY)
    public static class PropertyCompositeRef {
        private Long id;
        private PropertyCompositeTarget target;
        boolean targetSetterInvoked;

        public PropertyCompositeRef() {
        }

        PropertyCompositeRef(PropertyCompositeTarget target) {
            this.target = target;
        }

        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        @ManyToOne
        @JoinColumns({
                @JoinColumn(name = "target_code_fk", referencedColumnName = "target_code"),
                @JoinColumn(name = "target_sequence_fk", referencedColumnName = "target_sequence")
        })
        public PropertyCompositeTarget getTarget() {
            return target;
        }

        public void setTarget(PropertyCompositeTarget target) {
            targetSetterInvoked = true;
            this.target = target;
        }
    }
}
