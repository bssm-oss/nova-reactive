package io.nova.fetch;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import io.nova.metadata.DefaultNamingStrategy;
import io.nova.metadata.EntityMetadataFactory;
import io.nova.support.fixtures.FixtureEntities.AuthorWithBooksAnnotated;
import io.nova.support.fixtures.FixtureEntities.BookWithAuthorAnnotated;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AnnotationFetchGroupBuilder}가 {@link io.nova.metadata.EntityMetadata}의 관계 marker를 보고
 * {@link FetchGroup}을 구성하는지 검증한다.
 */
class AnnotationFetchGroupBuilderTest {
    private final EntityMetadataFactory factory = new EntityMetadataFactory(new DefaultNamingStrategy());
    private final AnnotationFetchGroupBuilder builder = new AnnotationFetchGroupBuilder(factory);

    @Test
    void buildForAuthorEmitsListSpecResolvedFromMappedBy() {
        FetchGroup<AuthorWithBooksAnnotated> group = builder.buildFor(AuthorWithBooksAnnotated.class);

        assertSame(AuthorWithBooksAnnotated.class, group.parentType());
        assertEquals(1, group.specs().size());
        FetchGroup.FetchSpec<AuthorWithBooksAnnotated, ?> spec = group.specs().get(0);
        assertSame(BookWithAuthorAnnotated.class, spec.childType());
        assertEquals("author_id", spec.childForeignKeyColumn(),
                "mappedBy 가 child entity의 @ManyToOne(author) property를 가리키고, 그 columnName 이 FK 컬럼이다");
        assertFalse(spec.single(), "@OneToMany는 list-기반 spec이므로 single=false");
        assertNotNull(spec.parentIdExtractor());
        assertNotNull(spec.setter());
    }

    @Test
    void buildForBookEmitsSingleSpecResolvedFromManyToOneTarget() {
        FetchGroup<BookWithAuthorAnnotated> group = builder.buildFor(BookWithAuthorAnnotated.class);

        assertEquals(1, group.specs().size());
        FetchGroup.FetchSpec<BookWithAuthorAnnotated, ?> spec = group.specs().get(0);
        assertSame(AuthorWithBooksAnnotated.class, spec.childType());
        assertEquals("id", spec.childForeignKeyColumn(),
                "@ManyToOne 측 spec은 target entity의 PK 컬럼으로 IN-query를 발행한다");
        assertTrue(spec.single(), "@ManyToOne 측은 단건 reference이므로 single=true");
    }

    @Test
    void buildForEntityWithoutRelationsReturnsEmptyGroup() {
        FetchGroup<PlainEntity> group = builder.buildFor(PlainEntity.class);
        assertTrue(group.specs().isEmpty());
    }

    @Test
    void buildForRejectsMappedByThatDoesNotExistOnChild() {
        assertThrows(IllegalStateException.class, () -> builder.buildFor(AuthorWithUnknownMappedBy.class));
    }

    @Test
    void buildForRejectsMappedByPointingToNonManyToOneProperty() {
        assertThrows(IllegalStateException.class, () -> builder.buildFor(AuthorWithNonManyToOneMappedBy.class));
    }

    @Test
    void buildForRejectsNullParentType() {
        assertThrows(NullPointerException.class, () -> builder.buildFor(null));
    }

    @Test
    void singleSetterInjectsFirstChildAndNullForEmptyBucket() {
        FetchGroup<BookWithAuthorAnnotated> group = builder.buildFor(BookWithAuthorAnnotated.class);
        BookWithAuthorAnnotated book = new BookWithAuthorAnnotated(1L, "x", null);
        AuthorWithBooksAnnotated author = new AuthorWithBooksAnnotated(7L, "ada");
        @SuppressWarnings({"unchecked", "rawtypes"})
        FetchGroup.FetchSpec<BookWithAuthorAnnotated, AuthorWithBooksAnnotated> spec =
                (FetchGroup.FetchSpec) group.specs().get(0);

        spec.setter().accept(book, List.of(author));
        assertSame(author, book.getAuthor());

        spec.setter().accept(book, List.of());
        assertEquals(null, book.getAuthor(), "empty bucket은 reference를 null로 되돌려야 한다");
    }

    @Entity
    static class PlainEntity {
        @Id
        private Long id;

        PlainEntity() {
        }
    }

    @Entity
    static class AuthorWithUnknownMappedBy {
        @Id
        private Long id;

        @OneToMany(targetEntity = BookWithAuthorAnnotated.class, mappedBy = "nonexistent")
        private java.util.List<BookWithAuthorAnnotated> children;

        AuthorWithUnknownMappedBy() {
        }
    }

    @Entity
    static class AuthorWithNonManyToOneMappedBy {
        @Id
        private Long id;

        // child의 'title' field는 @ManyToOne이 아니므로 mappedBy 대상이 될 수 없다.
        @OneToMany(targetEntity = BookWithAuthorAnnotated.class, mappedBy = "title")
        private java.util.List<BookWithAuthorAnnotated> children;

        AuthorWithNonManyToOneMappedBy() {
        }
    }

}
