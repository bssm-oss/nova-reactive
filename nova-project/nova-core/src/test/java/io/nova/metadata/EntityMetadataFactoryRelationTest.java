package io.nova.metadata;

import io.nova.annotation.CreatedAt;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import io.nova.annotation.SoftDelete;
import io.nova.annotation.UpdatedAt;
import jakarta.persistence.Version;
import io.nova.support.fixtures.FixtureEntities.AuthorBookJoinColumnConflict;
import io.nova.support.fixtures.FixtureEntities.AuthorWithBooksAnnotated;
import io.nova.support.fixtures.FixtureEntities.BookWithAuthorAnnotated;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link EntityMetadataFactory}가 {@code @ManyToOne}, {@code @OneToMany}, {@code @JoinColumn}을
 * 읽어 marker property를 만들고, 양립 불가능한 조합과 column 충돌을 거부하는지 검증한다.
 */
class EntityMetadataFactoryRelationTest {
    private final EntityMetadataFactory factory = new EntityMetadataFactory(new DefaultNamingStrategy());

    @Test
    void manyToOnePropertyExposesFkColumnAndTargetType() {
        EntityMetadata<BookWithAuthorAnnotated> metadata = factory.getEntityMetadata(BookWithAuthorAnnotated.class);

        List<PersistentProperty> relations = metadata.manyToOneProperties();
        assertEquals(1, relations.size());
        PersistentProperty author = relations.get(0);
        assertEquals("author", author.propertyName());
        assertEquals("author_id", author.columnName(), "@JoinColumn.name overrides default naming");
        assertTrue(author.manyToOne());
        assertFalse(author.oneToMany());
        assertSame(AuthorWithBooksAnnotated.class, author.manyToOneTargetType());
        assertTrue(author.isRelation());
    }

    @Test
    void manyToOneWithoutJoinColumnFallsBackToDefaultNaming() {
        EntityMetadata<BookWithDefaultFkName> metadata = factory.getEntityMetadata(BookWithDefaultFkName.class);

        List<PersistentProperty> relations = metadata.manyToOneProperties();
        assertEquals(1, relations.size());
        // default naming = snake_case(propertyName + "_id") => "owner_id"
        assertEquals("owner_id", relations.get(0).columnName());
    }

    @Test
    void oneToManyMarkerHasNoColumnAndCarriesMappedBy() {
        EntityMetadata<AuthorWithBooksAnnotated> metadata = factory.getEntityMetadata(AuthorWithBooksAnnotated.class);

        List<PersistentProperty> inverse = metadata.oneToManyProperties();
        assertEquals(1, inverse.size());
        PersistentProperty books = inverse.get(0);
        assertEquals("books", books.propertyName());
        assertEquals("author", books.oneToManyMappedBy());
        assertSame(BookWithAuthorAnnotated.class, books.oneToManyTargetType());
        assertTrue(books.oneToMany());
        assertFalse(books.manyToOne());
        // properties()는 raw list라 books도 포함되지만, columnMappedProperties()는 컬럼 없는
        // marker를 제외하므로 books는 그 결과에서 제외된다.
        assertTrue(metadata.properties().stream().anyMatch(p -> p.propertyName().equals("books")));
        assertFalse(metadata.columnMappedProperties().stream().anyMatch(p -> p.propertyName().equals("books")));
    }

    @Test
    void joinColumnConflictWithRegularColumnIsRejected() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> factory.getEntityMetadata(AuthorBookJoinColumnConflict.class));
        assertTrue(error.getMessage().contains("duplicate column"));
        assertTrue(error.getMessage().contains("author_id"));
    }

    @Test
    void manyToOneAndOneToManyOnSameFieldRejected() {
        assertThrows(IllegalStateException.class, () -> factory.getEntityMetadata(BothRelationsEntity.class));
    }

    @Test
    void embeddedAndRelationOnSameFieldRejected() {
        assertThrows(IllegalStateException.class, () -> factory.getEntityMetadata(EmbeddedAndManyToOneEntity.class));
    }

    @Test
    void idAndManyToOneOnSameFieldRejected() {
        assertThrows(IllegalStateException.class, () -> factory.getEntityMetadata(IdAndManyToOneEntity.class));
    }

    @Test
    void versionAndOneToManyOnSameFieldRejected() {
        assertThrows(IllegalStateException.class, () -> factory.getEntityMetadata(VersionAndOneToManyEntity.class));
    }

    @Test
    void softDeleteAndManyToOneOnSameFieldRejected() {
        assertThrows(IllegalStateException.class, () -> factory.getEntityMetadata(SoftDeleteAndManyToOneEntity.class));
    }

    @Test
    void createdAtAndManyToOneOnSameFieldRejected() {
        assertThrows(IllegalStateException.class, () -> factory.getEntityMetadata(CreatedAtAndManyToOneEntity.class));
    }

    @Test
    void updatedAtAndManyToOneOnSameFieldRejected() {
        assertThrows(IllegalStateException.class, () -> factory.getEntityMetadata(UpdatedAtAndManyToOneEntity.class));
    }

    @Test
    void enumeratedAndOneToManyOnSameFieldRejected() {
        assertThrows(IllegalStateException.class, () -> factory.getEntityMetadata(EnumeratedAndOneToManyEntity.class));
    }

    @Test
    void oneToManyWithBlankMappedByRejected() {
        assertThrows(IllegalStateException.class, () -> factory.getEntityMetadata(BlankMappedByEntity.class));
    }

    @Test
    void manyToOneNullabilityIsStrictWhenJoinColumnNullableIsFalse() {
        EntityMetadata<NonNullJoinColumnEntity> metadata =
                factory.getEntityMetadata(NonNullJoinColumnEntity.class);
        PersistentProperty author = metadata.manyToOneProperties().get(0);
        assertFalse(author.manyToOneNullable());
        assertFalse(author.nullable());
    }

    @Test
    void hasRelationPropertiesReflectsAnnotationsPresent() {
        assertTrue(factory.getEntityMetadata(AuthorWithBooksAnnotated.class).hasRelationProperties());
        assertTrue(factory.getEntityMetadata(BookWithAuthorAnnotated.class).hasRelationProperties());
    }

    @Entity
    static class BookWithDefaultFkName {
        @Id
        private Long id;

        @ManyToOne(targetEntity = AuthorWithBooksAnnotated.class)
        private AuthorWithBooksAnnotated owner;

        BookWithDefaultFkName() {
        }
    }

    @Entity
    static class BothRelationsEntity {
        @Id
        private Long id;

        @ManyToOne
        @OneToMany(mappedBy = "x")
        private Object bad;

        BothRelationsEntity() {
        }
    }

    @Embeddable
    static class EmbeddedRelationAddress {
        private String city;

        EmbeddedRelationAddress() {
        }
    }

    @Entity
    static class EmbeddedAndManyToOneEntity {
        @Id
        private Long id;

        @Embedded
        @ManyToOne(targetEntity = AuthorWithBooksAnnotated.class)
        private EmbeddedRelationAddress bad;

        EmbeddedAndManyToOneEntity() {
        }
    }

    @Entity
    static class IdAndManyToOneEntity {
        @Id
        @ManyToOne(targetEntity = AuthorWithBooksAnnotated.class)
        private AuthorWithBooksAnnotated bad;

        IdAndManyToOneEntity() {
        }
    }

    @Entity
    static class VersionAndOneToManyEntity {
        @Id
        private Long id;

        @Version
        @OneToMany(targetEntity = BookWithAuthorAnnotated.class, mappedBy = "author")
        private Long bad;

        VersionAndOneToManyEntity() {
        }
    }

    @Entity
    static class SoftDeleteAndManyToOneEntity {
        @Id
        private Long id;

        @SoftDelete
        @ManyToOne(targetEntity = AuthorWithBooksAnnotated.class)
        private AuthorWithBooksAnnotated bad;

        SoftDeleteAndManyToOneEntity() {
        }
    }

    @Entity
    static class CreatedAtAndManyToOneEntity {
        @Id
        private Long id;

        @CreatedAt
        @ManyToOne(targetEntity = AuthorWithBooksAnnotated.class)
        private Instant bad;

        CreatedAtAndManyToOneEntity() {
        }
    }

    @Entity
    static class UpdatedAtAndManyToOneEntity {
        @Id
        private Long id;

        @UpdatedAt
        @ManyToOne(targetEntity = AuthorWithBooksAnnotated.class)
        private Instant bad;

        UpdatedAtAndManyToOneEntity() {
        }
    }

    @Entity
    static class EnumeratedAndOneToManyEntity {
        @Id
        private Long id;

        @Enumerated
        @OneToMany(targetEntity = BookWithAuthorAnnotated.class, mappedBy = "author")
        private Status bad;

        EnumeratedAndOneToManyEntity() {
        }
    }

    enum Status {
        A, B
    }

    @Entity
    static class BlankMappedByEntity {
        @Id
        private Long id;

        @OneToMany(targetEntity = BookWithAuthorAnnotated.class, mappedBy = "")
        private java.util.List<BookWithAuthorAnnotated> children;

        BlankMappedByEntity() {
        }
    }

    @Entity
    static class NonNullJoinColumnEntity {
        @Id
        private Long id;

        @ManyToOne(targetEntity = AuthorWithBooksAnnotated.class, optional = false)
        @JoinColumn(name = "owner_id", nullable = false)
        private AuthorWithBooksAnnotated owner;

        NonNullJoinColumnEntity() {
        }
    }

}
