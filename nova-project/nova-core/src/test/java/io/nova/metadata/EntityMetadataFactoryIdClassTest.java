package io.nova.metadata;

import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code @IdClass} 복합키의 메타데이터 추출(top-level @Id 컬럼들을 복합 id로)과 거부 규칙을 보호한다.
 */
class EntityMetadataFactoryIdClassTest {
    private final EntityMetadataFactory factory = new EntityMetadataFactory(new DefaultNamingStrategy());

    @Test
    void marksEachIdFieldAsCompositeIdColumn() {
        EntityMetadata<Book> metadata = factory.getEntityMetadata(Book.class);

        assertTrue(metadata.hasCompositeId());
        List<PersistentProperty> ids = metadata.idProperties();
        assertEquals(2, ids.size());
        assertTrue(ids.stream().allMatch(PersistentProperty::id));
        // @IdClass 컴포넌트는 top-level 컬럼이다(@EmbeddedId와 달리 embedded가 아님, dotted name 없음).
        assertFalse(ids.stream().anyMatch(PersistentProperty::embedded));
        assertEquals("publisherId", ids.get(0).propertyName());
        assertEquals("isbn", ids.get(1).propertyName());
        assertEquals("publisher_id", ids.get(0).columnName());
        assertEquals("isbn", ids.get(1).columnName());

        // id 컬럼은 INSERT에 포함되고 UPDATE SET에서는 제외된다.
        assertTrue(metadata.insertableProperties().stream().anyMatch(p -> p.columnName().equals("publisher_id")));
        assertFalse(metadata.updatableProperties().stream().anyMatch(PersistentProperty::id));
    }

    @Test
    void readIdValueBuildsIdClassInstanceFromEntity() {
        EntityMetadata<Book> metadata = factory.getEntityMetadata(Book.class);
        Book book = new Book(7L, "978-1", "Reactive");

        Object id = metadata.readIdValue(book);
        assertTrue(id instanceof BookId);
        BookId bookId = (BookId) id;
        assertEquals(7L, bookId.publisherId);
        assertEquals("978-1", bookId.isbn);

        // 분해도 같은 값을 돌려줘야 한다(selectById/deleteById 바인딩 경로).
        assertEquals(7L, metadata.idColumnValue(metadata.idProperties().get(0), id));
        assertEquals("978-1", metadata.idColumnValue(metadata.idProperties().get(1), id));
    }

    @Test
    void rejectsIdClassMissingField() {
        assertThrows(IllegalArgumentException.class, () -> factory.getEntityMetadata(MissingFieldEntity.class));
    }

    @Test
    void rejectsIdClassFieldTypeMismatch() {
        assertThrows(IllegalArgumentException.class, () -> factory.getEntityMetadata(TypeMismatchEntity.class));
    }

    @Test
    void rejectsIdClassWithSingleIdField() {
        assertThrows(IllegalArgumentException.class, () -> factory.getEntityMetadata(SingleIdEntity.class));
    }

    @Test
    void rejectsMultipleIdWithoutIdClass() {
        assertThrows(IllegalArgumentException.class, () -> factory.getEntityMetadata(MultipleIdNoIdClass.class));
    }

    @Test
    void rejectsCombiningIdClassWithEmbeddedId() {
        assertThrows(IllegalArgumentException.class, () -> factory.getEntityMetadata(IdClassWithEmbeddedId.class));
    }

    // --- fixtures -----------------------------------------------------------

    public static class BookId {
        Long publisherId;
        String isbn;

        public BookId() {
        }

        public BookId(Long publisherId, String isbn) {
            this.publisherId = publisherId;
            this.isbn = isbn;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof BookId that
                    && Objects.equals(publisherId, that.publisherId) && Objects.equals(isbn, that.isbn);
        }

        @Override
        public int hashCode() {
            return Objects.hash(publisherId, isbn);
        }
    }

    @Entity
    @Table(name = "book")
    @IdClass(BookId.class)
    static class Book {
        @Id
        Long publisherId;
        @Id
        String isbn;
        String title;

        Book() {
        }

        Book(Long publisherId, String isbn, String title) {
            this.publisherId = publisherId;
            this.isbn = isbn;
            this.title = title;
        }
    }

    public static class WrongId {
        Long publisherId;
        // missing 'isbn'
    }

    @Entity
    @Table(name = "missing_field")
    @IdClass(WrongId.class)
    static class MissingFieldEntity {
        @Id
        Long publisherId;
        @Id
        String isbn;
    }

    public static class MismatchedId {
        Long publisherId;
        Long isbn; // should be String
    }

    @Entity
    @Table(name = "type_mismatch")
    @IdClass(MismatchedId.class)
    static class TypeMismatchEntity {
        @Id
        Long publisherId;
        @Id
        String isbn;
    }

    public static class SoloId {
        Long onlyKey;
    }

    @Entity
    @Table(name = "single_id")
    @IdClass(SoloId.class)
    static class SingleIdEntity {
        @Id
        Long onlyKey;
    }

    @Entity
    @Table(name = "multi_no_idclass")
    static class MultipleIdNoIdClass {
        @Id
        Long a;
        @Id
        Long b;
    }

    @Embeddable
    public static class ComboKey {
        Long a;
        Long b;
    }

    @Entity
    @Table(name = "idclass_embedded")
    @IdClass(BookId.class)
    static class IdClassWithEmbeddedId {
        @EmbeddedId
        ComboKey key;
    }
}
