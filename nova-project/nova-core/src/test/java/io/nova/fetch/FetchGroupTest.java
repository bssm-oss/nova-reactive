package io.nova.fetch;

import io.nova.support.fixtures.FixtureEntities.Author;
import io.nova.support.fixtures.FixtureEntities.Book;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FetchGroupTest {
    @Test
    void forParentsRejectsNullParentType() {
        assertThrows(NullPointerException.class, () -> FetchGroup.forParents(null));
    }

    @Test
    void builderRejectsNullChildType() {
        FetchGroup.Builder<Author> builder = FetchGroup.forParents(Author.class);
        assertThrows(NullPointerException.class,
                () -> builder.with(null, "author_id", Author::getId, Author::setBooks));
    }

    @Test
    void builderRejectsNullForeignKeyColumn() {
        FetchGroup.Builder<Author> builder = FetchGroup.forParents(Author.class);
        assertThrows(NullPointerException.class,
                () -> builder.with(Book.class, null, Author::getId, Author::setBooks));
    }

    @Test
    void builderRejectsBlankForeignKeyColumn() {
        FetchGroup.Builder<Author> builder = FetchGroup.forParents(Author.class);
        assertThrows(IllegalArgumentException.class,
                () -> builder.with(Book.class, "   ", Author::getId, Author::setBooks));
    }

    @Test
    void builderRejectsNullExtractor() {
        FetchGroup.Builder<Author> builder = FetchGroup.forParents(Author.class);
        assertThrows(NullPointerException.class,
                () -> builder.with(Book.class, "author_id", null, Author::setBooks));
    }

    @Test
    void builderRejectsNullSetter() {
        FetchGroup.Builder<Author> builder = FetchGroup.forParents(Author.class);
        assertThrows(NullPointerException.class,
                () -> builder.with(Book.class, "author_id", Author::getId, null));
    }

    @Test
    void buildExposesParentTypeAndSpecsInOrder() {
        FetchGroup<Author> group = FetchGroup.forParents(Author.class)
                .with(Book.class, "author_id", Author::getId, Author::setBooks)
                .build();

        assertSame(Author.class, group.parentType());
        assertEquals(1, group.specs().size());
        FetchGroup.FetchSpec<Author, ?> spec = group.specs().get(0);
        assertSame(Book.class, spec.childType());
        assertEquals("author_id", spec.childForeignKeyColumn());
        assertNotNull(spec.parentIdExtractor());
        assertNotNull(spec.setter());
    }

    @Test
    void buildReturnsImmutableSpecList() {
        FetchGroup<Author> group = FetchGroup.forParents(Author.class)
                .with(Book.class, "author_id", Author::getId, Author::setBooks)
                .build();

        assertThrows(UnsupportedOperationException.class,
                () -> group.specs().add(null));
    }

    @Test
    void builderAccumulatesMultipleSpecs() {
        FetchGroup<Author> group = FetchGroup.forParents(Author.class)
                .with(Book.class, "author_id", Author::getId, Author::setBooks)
                .with(Book.class, "author_id", Author::getId, Author::setBooks)
                .build();

        assertEquals(2, group.specs().size());
    }

    @Test
    void emptyFetchGroupExposesEmptySpecs() {
        FetchGroup<Author> group = FetchGroup.forParents(Author.class).build();
        assertTrue(group.specs().isEmpty());
    }

    @Test
    void buildSnapshotIsNotAffectedByFurtherBuilderMutation() {
        FetchGroup.Builder<Author> builder = FetchGroup.forParents(Author.class)
                .with(Book.class, "author_id", Author::getId, Author::setBooks);
        FetchGroup<Author> snapshot = builder.build();
        builder.with(Book.class, "author_id", Author::getId, Author::setBooks);

        assertEquals(1, snapshot.specs().size(), "이미 build된 snapshot은 후속 with()에 영향받지 않아야 한다");
    }

    @Test
    void specSetterCanInjectChildrenIntoParent() {
        Author author = new Author(1L, "ada");
        FetchGroup<Author> group = FetchGroup.forParents(Author.class)
                .with(Book.class, "author_id", Author::getId, Author::setBooks)
                .build();
        FetchGroup.FetchSpec<Author, ?> spec = group.specs().get(0);
        @SuppressWarnings({"unchecked", "rawtypes"})
        FetchGroup.FetchSpec<Author, Book> typed = (FetchGroup.FetchSpec) spec;
        List<Book> books = List.of(new Book(10L, "x", 1L));
        typed.setter().accept(author, books);

        assertSame(books, author.getBooks());
    }
}
