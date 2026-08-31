package io.nova.metamodel;

import io.nova.metamodel.ProcessorRunner.Compilation;
import io.nova.metamodel.ProcessorRunner.Source;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.lang.model.SourceVersion;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MetamodelProcessorTest {

    @Test
    @DisplayName("프로세서는 실행 중인 JDK의 source level을 지원한다고 선언한다")
    void advertisesRunningCompilerSourceVersion() {
        assertEquals(SourceVersion.latestSupported(), new MetamodelProcessor().getSupportedSourceVersion());
    }

    @Test
    @DisplayName("@Entity의 평탄 필드는 propertyName 그대로 상수로 발행된다")
    void emitsFlatFieldsAsConstants() {
        Source source = new Source(
                "fixtures.Author",
                """
                package fixtures;

                import jakarta.persistence.Column;
                import jakarta.persistence.Entity;
                import jakarta.persistence.Id;

                @Entity
                public class Author {
                    @Id
                    private Long id;

                    @Column
                    private String email;

                    private boolean active;
                }
                """);

        Compilation compilation = ProcessorRunner.compile(source);

        assertCompilationSucceeded(compilation);
        String generated = compilation.generatedSources().get("fixtures.Author_");
        assertNotNull(generated, "expected fixtures.Author_ to be generated");
        assertTrue(generated.contains("public static final String id = \"id\";"),
                () -> "missing id constant in:\n" + generated);
        assertTrue(generated.contains("public static final String email = \"email\";"),
                () -> "missing email constant in:\n" + generated);
        assertTrue(generated.contains("public static final String active = \"active\";"),
                () -> "missing active constant in:\n" + generated);
        assertTrue(generated.contains("public final class Author_"),
                () -> "expected JPA-style Author_ class name in:\n" + generated);
    }

    @Test
    @DisplayName("@Embedded 필드는 host.leaf dot-notation 값과 host_leaf safe identifier로 평탄화된다")
    void flattensEmbeddedFields() {
        Source entity = new Source(
                "fixtures.Customer",
                """
                package fixtures;

                import jakarta.persistence.Embedded;
                import jakarta.persistence.Entity;
                import jakarta.persistence.Id;

                @Entity
                public class Customer {
                    @Id
                    private Long id;

                    @Embedded
                    private Address address;
                }
                """);
        Source embeddable = new Source(
                "fixtures.Address",
                """
                package fixtures;

                import jakarta.persistence.Embeddable;

                @Embeddable
                public class Address {
                    private String street;
                    private String city;
                }
                """);

        Compilation compilation = ProcessorRunner.compile(entity, embeddable);

        assertCompilationSucceeded(compilation);
        String generated = compilation.generatedSources().get("fixtures.Customer_");
        assertNotNull(generated, "expected fixtures.Customer_ to be generated");
        assertTrue(generated.contains("public static final String address_street = \"address.street\";"),
                () -> "missing flattened address.street in:\n" + generated);
        assertTrue(generated.contains("public static final String address_city = \"address.city\";"),
                () -> "missing flattened address.city in:\n" + generated);
        assertFalse(generated.contains("public static final String address ="),
                () -> "should not emit a constant for the @Embedded host field itself in:\n" + generated);
    }

    @Test
    @DisplayName("2-level nested @Embedded도 outer.inner.leaf 경로로 평탄화된다")
    void flattensNestedEmbedded() {
        Source entity = new Source(
                "fixtures.Shop",
                """
                package fixtures;

                import jakarta.persistence.Embedded;
                import jakarta.persistence.Entity;
                import jakarta.persistence.Id;

                @Entity
                public class Shop {
                    @Id
                    private Long id;

                    @Embedded
                    private Location location;
                }
                """);
        Source outer = new Source(
                "fixtures.Location",
                """
                package fixtures;

                import jakarta.persistence.Embeddable;
                import jakarta.persistence.Embedded;

                @Embeddable
                public class Location {
                    @Embedded
                    private Geo geo;
                }
                """);
        Source inner = new Source(
                "fixtures.Geo",
                """
                package fixtures;

                import jakarta.persistence.Embeddable;

                @Embeddable
                public class Geo {
                    private double latitude;
                    private double longitude;
                }
                """);

        Compilation compilation = ProcessorRunner.compile(entity, outer, inner);

        assertCompilationSucceeded(compilation);
        String generated = compilation.generatedSources().get("fixtures.Shop_");
        assertNotNull(generated, "expected fixtures.Shop_ to be generated");
        assertTrue(generated.contains("public static final String location_geo_latitude = \"location.geo.latitude\";"),
                () -> "missing 2-level flattened latitude in:\n" + generated);
        assertTrue(generated.contains("public static final String location_geo_longitude = \"location.geo.longitude\";"),
                () -> "missing 2-level flattened longitude in:\n" + generated);
    }

    @Test
    @DisplayName("@OneToMany inverse property는 컬럼이 없으므로 상수 발행 대상에서 제외된다")
    void skipsOneToManyInverseFields() {
        Source author = new Source(
                "fixtures.Author",
                """
                package fixtures;

                import jakarta.persistence.Entity;
                import jakarta.persistence.Id;
                import jakarta.persistence.OneToMany;

                import java.util.List;

                @Entity
                public class Author {
                    @Id
                    private Long id;

                    private String name;

                    @OneToMany(targetEntity = Book.class, mappedBy = "author")
                    private List<Book> books;
                }
                """);
        Source book = new Source(
                "fixtures.Book",
                """
                package fixtures;

                import jakarta.persistence.Entity;
                import jakarta.persistence.Id;
                import jakarta.persistence.ManyToOne;

                @Entity
                public class Book {
                    @Id
                    private Long id;

                    @ManyToOne
                    private Author author;
                }
                """);

        Compilation compilation = ProcessorRunner.compile(author, book);

        assertCompilationSucceeded(compilation);
        Map<String, String> generated = compilation.generatedSources();
        String authorMeta = generated.get("fixtures.Author_");
        assertNotNull(authorMeta, "expected fixtures.Author_ to be generated");
        assertFalse(authorMeta.contains("books"),
                () -> "@OneToMany books property should not appear in:\n" + authorMeta);
        assertTrue(authorMeta.contains("public static final String name = \"name\";"),
                () -> "regular name field should still be emitted in:\n" + authorMeta);

        String bookMeta = generated.get("fixtures.Book_");
        assertNotNull(bookMeta, "expected fixtures.Book_ to be generated");
        assertTrue(bookMeta.contains("public static final String author = \"author\";"),
                () -> "@ManyToOne owning side should emit FK property in:\n" + bookMeta);
    }

    @Test
    @DisplayName("FIELD access emits only column-backed association constants")
    void emitsOnlyColumnBackedFieldAssociations() {
        Source source = new Source(
                "fixtures.FieldAssociations",
                """
                package fixtures;

                import jakarta.persistence.ElementCollection;
                import jakarta.persistence.Entity;
                import jakarta.persistence.Id;
                import jakarta.persistence.ManyToMany;
                import jakarta.persistence.ManyToOne;
                import jakarta.persistence.OneToOne;

                import java.util.List;
                import java.util.Set;

                @Entity
                public class FieldAssociations {
                    @Id private Long id;
                    @ManyToOne private Parent parent;
                    @OneToOne private Profile profile;
                    @OneToOne(mappedBy = "fieldAssociations") private Detail detail;
                    @ManyToMany private Set<Tag> tags;
                    @ElementCollection private List<String> aliases;
                }

                class Parent { }
                class Profile { }
                class Detail { }
                class Tag { }
                """);

        Compilation compilation = ProcessorRunner.compile(source);

        assertCompilationSucceeded(compilation);
        String generated = compilation.generatedSources().get("fixtures.FieldAssociations_");
        assertNotNull(generated);
        assertTrue(generated.contains("public static final String parent = \"parent\";"));
        assertTrue(generated.contains("public static final String profile = \"profile\";"));
        assertFalse(generated.contains("detail"));
        assertFalse(generated.contains("tags"));
        assertFalse(generated.contains("aliases"));
    }

    @Test
    @DisplayName("PROPERTY access emits only column-backed association constants")
    void emitsOnlyColumnBackedPropertyAssociations() {
        Source source = new Source(
                "fixtures.PropertyAssociations",
                """
                package fixtures;

                import jakarta.persistence.Access;
                import jakarta.persistence.AccessType;
                import jakarta.persistence.ElementCollection;
                import jakarta.persistence.Entity;
                import jakarta.persistence.Id;
                import jakarta.persistence.ManyToMany;
                import jakarta.persistence.ManyToOne;
                import jakarta.persistence.OneToOne;

                import java.util.List;
                import java.util.Set;

                @Entity
                @Access(AccessType.PROPERTY)
                public class PropertyAssociations {
                    private Long id;
                    private Parent parent;
                    private Profile profile;
                    private Detail detail;
                    private Set<Tag> tags;
                    private List<String> aliases;

                    @Id public Long getId() { return id; }
                    public void setId(Long id) { this.id = id; }
                    @ManyToOne public Parent getParent() { return parent; }
                    public void setParent(Parent parent) { this.parent = parent; }
                    @OneToOne public Profile getProfile() { return profile; }
                    public void setProfile(Profile profile) { this.profile = profile; }
                    @OneToOne(mappedBy = "propertyAssociations") public Detail getDetail() { return detail; }
                    public void setDetail(Detail detail) { this.detail = detail; }
                    @ManyToMany public Set<Tag> getTags() { return tags; }
                    public void setTags(Set<Tag> tags) { this.tags = tags; }
                    @ElementCollection public List<String> getAliases() { return aliases; }
                    public void setAliases(List<String> aliases) { this.aliases = aliases; }
                }

                class Parent { }
                class Profile { }
                class Detail { }
                class Tag { }
                """);

        Compilation compilation = ProcessorRunner.compile(source);

        assertCompilationSucceeded(compilation);
        String generated = compilation.generatedSources().get("fixtures.PropertyAssociations_");
        assertNotNull(generated);
        assertTrue(generated.contains("public static final String parent = \"parent\";"));
        assertTrue(generated.contains("public static final String profile = \"profile\";"));
        assertFalse(generated.contains("detail"));
        assertFalse(generated.contains("tags"));
        assertFalse(generated.contains("aliases"));
    }

    @Test
    @DisplayName("static / transient / 합성 필드는 모두 무시된다")
    void ignoresStaticAndTransientFields() {
        Source source = new Source(
                "fixtures.Sample",
                """
                package fixtures;

                import jakarta.persistence.Entity;
                import jakarta.persistence.Id;

                @Entity
                public class Sample {
                    public static final long serialVersionUID = 1L;
                    public static String STATIC_NAME = "x";

                    @Id
                    private Long id;

                    private transient String cached;

                    private String real;
                }
                """);

        Compilation compilation = ProcessorRunner.compile(source);

        assertCompilationSucceeded(compilation);
        String generated = compilation.generatedSources().get("fixtures.Sample_");
        assertNotNull(generated, "expected fixtures.Sample_ to be generated");
        assertTrue(generated.contains("public static final String real ="),
                () -> "real field missing in:\n" + generated);
        assertTrue(generated.contains("public static final String id ="),
                () -> "id field missing in:\n" + generated);
        assertFalse(generated.contains("cached"),
                () -> "transient cached should be skipped in:\n" + generated);
        assertFalse(generated.contains("STATIC_NAME"),
                () -> "static STATIC_NAME should be skipped in:\n" + generated);
        assertFalse(generated.contains("serialVersionUID"),
                () -> "static serialVersionUID should be skipped in:\n" + generated);
    }

    @Test
    @DisplayName("PROPERTY 엔티티는 getter의 논리 이름만 metamodel에 발행한다")
    void emitsGetterOnlyPropertyNames() {
        Source source = new Source(
                "fixtures.PropertyAuthor",
                """
                package fixtures;

                import jakarta.persistence.Access;
                import jakarta.persistence.AccessType;
                import jakarta.persistence.Column;
                import jakarta.persistence.Entity;
                import jakarta.persistence.Id;
                import jakarta.persistence.Transient;

                @Entity
                @Access(AccessType.PROPERTY)
                public class PropertyAuthor {
                    private Long key;
                    private String backingEmail;

                    @Id
                    public Long getId() { return key; }
                    public void setId(Long id) { key = id; }

                    @Column(name = "email")
                    public String getEmail() { return backingEmail; }
                    public void setEmail(String email) { backingEmail = email; }

                    @Transient
                    public String getComputed() { return backingEmail + "!"; }
                }
                """);

        Compilation compilation = ProcessorRunner.compile(source);

        assertCompilationSucceeded(compilation);
        String generated = compilation.generatedSources().get("fixtures.PropertyAuthor_");
        assertNotNull(generated);
        assertTrue(generated.contains("public static final String id = \"id\";"));
        assertTrue(generated.contains("public static final String email = \"email\";"));
        assertFalse(generated.contains("key"));
        assertFalse(generated.contains("backingEmail"));
        assertFalse(generated.contains("computed"));
    }

    @Test
    @DisplayName("PROPERTY JavaBeans selection matches runtime boolean, setter-overload, and covariant rules")
    void acceptsRuntimeValidJavaBeansPropertyModel() {
        Source base = new Source(
                "fixtures.BeanBase",
                """
                package fixtures;

                import jakarta.persistence.Access;
                import jakarta.persistence.AccessType;
                import jakarta.persistence.Id;
                import jakarta.persistence.MappedSuperclass;

                @MappedSuperclass
                @Access(AccessType.PROPERTY)
                public class BeanBase {
                    private Long id;

                    @Id public Long getId() { return id; }
                    public void setId(Long id) { this.id = id; }

                    public CharSequence getLabel() { return ""; }
                    public void setLabel(CharSequence label) { }
                }
                """);
        Source entity = new Source(
                "fixtures.BeanEntity",
                """
                package fixtures;

                import jakarta.persistence.Entity;

                @Entity
                public class BeanEntity extends BeanBase {
                    private boolean active;
                    private String label;
                    private String name;

                    // Boxed Boolean isX is not a JavaBeans getter.
                    public Boolean isActive() { return Boolean.TRUE; }
                    public boolean getActive() { return active; }
                    public void setActive(boolean active) { this.active = active; }

                    @Override public String getLabel() { return label; }
                    public void setLabel(String label) { this.label = label; }

                    public String getName() { return name; }
                    public void setName(CharSequence name) { this.name = name.toString(); }
                    public void setName(String name) { this.name = name; }
                }
                """);

        Compilation compilation = ProcessorRunner.compile(base, entity);

        assertCompilationSucceeded(compilation);
        String generated = compilation.generatedSources().get("fixtures.BeanEntity_");
        assertNotNull(generated);
        assertTrue(generated.contains("public static final String active = \"active\";"));
        assertTrue(generated.contains("public static final String label = \"label\";"));
        assertTrue(generated.contains("public static final String name = \"name\";"));
    }

    @Test
    @DisplayName("상속된 PROPERTY getter와 member-level PROPERTY override는 논리 이름으로 발행된다")
    void emitsInheritedAndMemberPropertyAccessNames() {
        Source base = new Source(
                "fixtures.PropertyBase",
                """
                package fixtures;

                import jakarta.persistence.Access;
                import jakarta.persistence.AccessType;
                import jakarta.persistence.Id;
                import jakarta.persistence.MappedSuperclass;

                @MappedSuperclass
                @Access(AccessType.PROPERTY)
                public class PropertyBase {
                    private Long key;
                    @Id public Long getId() { return key; }
                    public void setId(Long id) { key = id; }
                }
                """);
        Source entity = new Source(
                "fixtures.InheritedPropertyEntity",
                """
                package fixtures;

                import jakarta.persistence.Access;
                import jakarta.persistence.AccessType;
                import jakarta.persistence.Column;
                import jakarta.persistence.Entity;

                @Entity
                public class InheritedPropertyEntity extends PropertyBase {
                    private String storage;
                    @Access(AccessType.PROPERTY)
                    @Column(name = "label")
                    public String getLabel() { return storage; }
                    public void setLabel(String label) { storage = label; }
                }
                """);

        Compilation compilation = ProcessorRunner.compile(base, entity);

        assertCompilationSucceeded(compilation);
        String generated = compilation.generatedSources().get("fixtures.InheritedPropertyEntity_");
        assertNotNull(generated);
        assertTrue(generated.contains("public static final String id = \"id\";"));
        assertTrue(generated.contains("public static final String label = \"label\";"));
        assertFalse(generated.contains("storage"));
    }

    @Test
    @DisplayName("PROPERTY @EmbeddedId record의 leaf는 getter 논리 경로로 평탄화된다")
    void flattensPropertyEmbeddedIdRecordLeaves() {
        Source entity = new Source(
                "fixtures.PropertyOrder",
                """
                package fixtures;

                import jakarta.persistence.Access;
                import jakarta.persistence.AccessType;
                import jakarta.persistence.EmbeddedId;
                import jakarta.persistence.Entity;

                @Entity
                @Access(AccessType.PROPERTY)
                public class PropertyOrder {
                    private OrderKey key;

                    @EmbeddedId
                    public OrderKey getId() { return key; }
                    public void setId(OrderKey id) { key = id; }
                }
                """);
        Source key = new Source(
                "fixtures.OrderKey",
                """
                package fixtures;

                import jakarta.persistence.Embeddable;

                @Embeddable
                public record OrderKey(String tenant, long number) {
                }
                """);

        Compilation compilation = ProcessorRunner.compile(entity, key);

        assertCompilationSucceeded(compilation);
        String generated = compilation.generatedSources().get("fixtures.PropertyOrder_");
        assertNotNull(generated);
        assertTrue(generated.contains("public static final String id_tenant = \"id.tenant\";"),
                () -> "missing EmbeddedId tenant leaf in:\n" + generated);
        assertTrue(generated.contains("public static final String id_number = \"id.number\";"),
                () -> "missing EmbeddedId number leaf in:\n" + generated);
    }

    @Test
    @DisplayName("PROPERTY getter @Embedded의 mutable leaf는 host getter 이름으로 평탄화된다")
    void flattensMutablePropertyEmbeddedGetter() {
        Source entity = new Source(
                "fixtures.PropertyCustomer",
                """
                package fixtures;

                import jakarta.persistence.Access;
                import jakarta.persistence.AccessType;
                import jakarta.persistence.Embedded;
                import jakarta.persistence.Entity;
                import jakarta.persistence.Id;

                @Entity
                @Access(AccessType.PROPERTY)
                public class PropertyCustomer {
                    private Long key;
                    private Address stored;

                    @Id public Long getId() { return key; }
                    public void setId(Long id) { key = id; }

                    @Embedded public Address getAddress() { return stored; }
                    public void setAddress(Address address) { stored = address; }
                }
                """);
        Source address = new Source(
                "fixtures.Address",
                """
                package fixtures;

                import jakarta.persistence.Access;
                import jakarta.persistence.AccessType;
                import jakarta.persistence.Embeddable;

                @Embeddable
                @Access(AccessType.PROPERTY)
                public class Address {
                    private String cityValue;
                    public String getCity() { return cityValue; }
                    public void setCity(String city) { cityValue = city; }
                }
                """);

        Compilation compilation = ProcessorRunner.compile(entity, address);

        assertCompilationSucceeded(compilation);
        String generated = compilation.generatedSources().get("fixtures.PropertyCustomer_");
        assertNotNull(generated);
        assertTrue(generated.contains("public static final String address_city = \"address.city\";"),
                () -> "missing getter embedded leaf in:\n" + generated);
    }

    @Nested
    @DisplayName("오류 경로")
    class ErrorCases {

        @Test
        @DisplayName("PROPERTY getter에 setter가 없으면 ERROR diagnostic이 보고된다")
        void rejectsPropertyWithoutSetter() {
            Source source = new Source(
                    "fixtures.ReadOnlyProperty",
                    """
                    package fixtures;

                    import jakarta.persistence.Access;
                    import jakarta.persistence.AccessType;
                    import jakarta.persistence.Entity;
                    import jakarta.persistence.Id;

                    @Entity
                    @Access(AccessType.PROPERTY)
                    public class ReadOnlyProperty {
                        @Id
                        public Long getId() { return 1L; }
                    }
                    """);

            Compilation compilation = ProcessorRunner.compile(source);

            assertFalse(compilation.success(), "a writable PROPERTY identifier requires a setter");
            Diagnostic<? extends JavaFileObject> error = compilation.firstError();
            assertNotNull(error);
            assertTrue(error.getMessage(null).contains("setter"),
                    () -> "expected missing-setter diagnostic, got: " + error.getMessage(null));
        }

        @Test
        @DisplayName("동일 순위 JavaBeans getter가 겹치면 ERROR diagnostic이 보고된다")
        void rejectsAmbiguousSameRankGetters() {
            Source source = new Source(
                    "fixtures.AmbiguousProperty",
                    """
                    package fixtures;

                    import jakarta.persistence.Access;
                    import jakarta.persistence.AccessType;
                    import jakarta.persistence.Entity;
                    import jakarta.persistence.Id;

                    @Entity
                    @Access(AccessType.PROPERTY)
                    public class AmbiguousProperty {
                        @Id public Long getId() { return 1L; }
                        public void setId(Long id) { }

                        public String getName() { return "one"; }
                        public String getname() { return "two"; }
                        public void setName(String name) { }
                    }
                    """);

            Compilation compilation = ProcessorRunner.compile(source);

            assertFalse(compilation.success(), "same-rank JavaBeans getters must be rejected");
            Diagnostic<? extends JavaFileObject> error = compilation.firstError();
            assertNotNull(error);
            assertTrue(error.getMessage(null).contains("ambiguous getter"),
                    () -> "expected ambiguous-getter diagnostic, got: " + error.getMessage(null));
        }

        @Test
        @DisplayName("record component accessor와 JavaBean getter가 충돌하면 ERROR diagnostic이 보고된다")
        void rejectsRecordAccessorCollidingWithJavaBeanGetter() {
            Source entity = new Source(
                    "fixtures.RecordHolder",
                    """
                    package fixtures;

                    import jakarta.persistence.Embedded;
                    import jakarta.persistence.Entity;
                    import jakarta.persistence.Id;

                    @Entity
                    public class RecordHolder {
                        @Id private Long id;
                        @Embedded private Name name;
                    }
                    """);
            Source embeddable = new Source(
                    "fixtures.Name",
                    """
                    package fixtures;

                    import jakarta.persistence.Embeddable;

                    @Embeddable
                    public record Name(String value) {
                        public String getValue() { return value; }
                    }
                    """);

            Compilation compilation = ProcessorRunner.compile(entity, embeddable);

            assertFalse(compilation.success(), "record accessor and JavaBean getter must not select arbitrarily");
            Diagnostic<? extends JavaFileObject> error = compilation.firstError();
            assertNotNull(error);
            assertTrue(error.getMessage(null).contains("ambiguous accessor"),
                    () -> "expected ambiguous-accessor diagnostic, got: " + error.getMessage(null));
        }

        @Test
        @DisplayName("field와 getter에 식별자를 혼합하면 ERROR diagnostic이 보고된다")
        void rejectsMixedIdentifierAccess() {
            Source source = new Source(
                    "fixtures.MixedIdentifier",
                    """
                    package fixtures;

                    import jakarta.persistence.Entity;
                    import jakarta.persistence.Id;

                    @Entity
                    public class MixedIdentifier {
                        @Id private Long fieldId;
                        @Id public Long getId() { return fieldId; }
                        public void setId(Long id) { fieldId = id; }
                    }
                    """);

            Compilation compilation = ProcessorRunner.compile(source);

            assertFalse(compilation.success(), "identifier mapping cannot mix field and property access");
            Diagnostic<? extends JavaFileObject> error = compilation.firstError();
            assertNotNull(error);
            assertTrue(error.getMessage(null).contains("mixes"),
                    () -> "expected mixed-access diagnostic, got: " + error.getMessage(null));
        }

        @Test
        @DisplayName("flatten 후 safe identifier가 충돌하면 ERROR diagnostic이 보고된다")
        void rejectsCollisionBetweenEmbeddedAndFlatField() {
            // address_city embedded path가 평탄 필드 address_city와 정확히 같은 식별자를 만든다.
            Source entity = new Source(
                    "fixtures.Conflict",
                    """
                    package fixtures;

                    import jakarta.persistence.Embedded;
                    import jakarta.persistence.Entity;
                    import jakarta.persistence.Id;

                    @Entity
                    public class Conflict {
                        @Id
                        private Long id;

                        private String address_city;

                        @Embedded
                        private Place address;
                    }
                    """);
            Source place = new Source(
                    "fixtures.Place",
                    """
                    package fixtures;

                    import jakarta.persistence.Embeddable;

                    @Embeddable
                    public class Place {
                        private String city;
                    }
                    """);

            Compilation compilation = ProcessorRunner.compile(entity, place);

            assertFalse(compilation.success(),
                    "expected compilation to fail due to identifier collision");
            Diagnostic<? extends JavaFileObject> error = compilation.firstError();
            assertNotNull(error, "expected at least one ERROR diagnostic");
            assertTrue(error.getMessage(null).contains("name collision"),
                    () -> "expected collision message, got: " + error.getMessage(null));
        }

        @Test
        @DisplayName("자기 자신을 @Embedded로 포함하는 사이클은 ERROR로 보고된다")
        void rejectsEmbeddedCycle() {
            Source recursive = new Source(
                    "fixtures.Loop",
                    """
                    package fixtures;

                    import jakarta.persistence.Embeddable;
                    import jakarta.persistence.Embedded;

                    @Embeddable
                    public class Loop {
                        @Embedded
                        private Loop self;
                    }
                    """);
            Source entity = new Source(
                    "fixtures.Holder",
                    """
                    package fixtures;

                    import jakarta.persistence.Embedded;
                    import jakarta.persistence.Entity;
                    import jakarta.persistence.Id;

                    @Entity
                    public class Holder {
                        @Id
                        private Long id;

                        @Embedded
                        private Loop loop;
                    }
                    """);

            Compilation compilation = ProcessorRunner.compile(recursive, entity);

            assertFalse(compilation.success(),
                    "expected compilation to fail due to embedded cycle");
            Diagnostic<? extends JavaFileObject> error = compilation.firstError();
            assertNotNull(error, "expected at least one ERROR diagnostic");
            assertTrue(error.getMessage(null).contains("cycle"),
                    () -> "expected cycle message, got: " + error.getMessage(null));
            assertNull(compilation.generatedSources().get("fixtures.Holder_"),
                    "no companion should be emitted when generation aborts on a cycle");
        }
    }

    private static void assertCompilationSucceeded(Compilation compilation) {
        if (!compilation.success()) {
            StringBuilder message = new StringBuilder("compilation failed:\n");
            for (Diagnostic<? extends JavaFileObject> diagnostic : compilation.diagnostics()) {
                message.append("  [")
                        .append(diagnostic.getKind())
                        .append("] ")
                        .append(diagnostic.getMessage(null))
                        .append('\n');
            }
            throw new AssertionError(message.toString());
        }
        assertEquals(0, compilation.diagnostics().stream()
                        .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
                        .count(),
                () -> "expected no ERROR diagnostics, got: " + compilation.diagnostics());
    }
}
