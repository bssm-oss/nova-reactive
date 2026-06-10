package io.nova.metamodel;

import io.nova.metamodel.ProcessorRunner.Compilation;
import io.nova.metamodel.ProcessorRunner.Source;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

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
    @DisplayName("@Entity의 평탄 필드는 propertyName 그대로 상수로 발행된다")
    void emitsFlatFieldsAsConstants() {
        Source source = new Source(
                "fixtures.Author",
                """
                package fixtures;

                import io.nova.annotation.Column;
                import io.nova.annotation.Entity;
                import io.nova.annotation.Id;

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

                import io.nova.annotation.Embedded;
                import io.nova.annotation.Entity;
                import io.nova.annotation.Id;

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

                import io.nova.annotation.Embeddable;

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

                import io.nova.annotation.Embedded;
                import io.nova.annotation.Entity;
                import io.nova.annotation.Id;

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

                import io.nova.annotation.Embeddable;
                import io.nova.annotation.Embedded;

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

                import io.nova.annotation.Embeddable;

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

                import io.nova.annotation.Entity;
                import io.nova.annotation.Id;
                import io.nova.annotation.OneToMany;

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

                import io.nova.annotation.Entity;
                import io.nova.annotation.Id;
                import io.nova.annotation.ManyToOne;

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
    @DisplayName("static / transient / 합성 필드는 모두 무시된다")
    void ignoresStaticAndTransientFields() {
        Source source = new Source(
                "fixtures.Sample",
                """
                package fixtures;

                import io.nova.annotation.Entity;
                import io.nova.annotation.Id;

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

    @Nested
    @DisplayName("오류 경로")
    class ErrorCases {

        @Test
        @DisplayName("flatten 후 safe identifier가 충돌하면 ERROR diagnostic이 보고된다")
        void rejectsCollisionBetweenEmbeddedAndFlatField() {
            // address_city embedded path가 평탄 필드 address_city와 정확히 같은 식별자를 만든다.
            Source entity = new Source(
                    "fixtures.Conflict",
                    """
                    package fixtures;

                    import io.nova.annotation.Embedded;
                    import io.nova.annotation.Entity;
                    import io.nova.annotation.Id;

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

                    import io.nova.annotation.Embeddable;

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

                    import io.nova.annotation.Embeddable;
                    import io.nova.annotation.Embedded;

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

                    import io.nova.annotation.Embedded;
                    import io.nova.annotation.Entity;
                    import io.nova.annotation.Id;

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
