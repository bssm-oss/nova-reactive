package io.nova.metadata;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code @ElementCollection}(기본 타입 원소) 메타데이터 추출과 거부 규칙을 보호한다.
 */
class EntityMetadataFactoryElementCollectionTest {
    private final DefaultNamingStrategy naming = new DefaultNamingStrategy();
    private final EntityMetadataFactory factory = new EntityMetadataFactory(naming);

    @Test
    void defaultsCollectionTableAndColumnNames() {
        EntityMetadata<Person> metadata = factory.getEntityMetadata(Person.class);
        PersistentProperty tags = metadata.findProperty("tags").orElseThrow();

        assertTrue(tags.elementCollection());
        ElementCollectionInfo info = tags.elementCollectionInfo();
        assertEquals(naming.tableName(Person.class) + "_" + naming.columnName("tags"), info.collectionTableName());
        assertEquals(naming.columnName("Person") + "_id", info.ownerForeignKeyColumn());
        assertEquals(naming.columnName("tags"), info.valueColumn());
        assertEquals(String.class, info.valueType());
        assertFalse(info.usesSet());

        // marker라 컬럼 매핑/관계 게이트 처리.
        assertFalse(metadata.columnMappedProperties().stream().anyMatch(p -> p.propertyName().equals("tags")));
        assertTrue(metadata.hasRelationProperties());
        assertEquals(1, metadata.elementCollectionProperties().size());
    }

    @Test
    void honorsCollectionTableAndColumnOverrides() {
        EntityMetadata<Article> metadata = factory.getEntityMetadata(Article.class);
        ElementCollectionInfo info = metadata.findProperty("labels").orElseThrow().elementCollectionInfo();
        assertEquals("article_label", info.collectionTableName());
        assertEquals("article_fk", info.ownerForeignKeyColumn());
        assertEquals("label", info.valueColumn());
        assertTrue(info.usesSet());
        assertEquals(Integer.class, info.valueType());
    }

    @Test
    void rejectsEmbeddableElement() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> factory.getEntityMetadata(EmbeddableElements.class));
        assertTrue(error.getMessage().contains("Embeddable"));
    }

    @Test
    void rejectsNonCollectionField() {
        assertThrows(IllegalArgumentException.class, () -> factory.getEntityMetadata(NonCollection.class));
    }

    // --- fixtures -----------------------------------------------------------

    @Entity
    @Table(name = "person")
    static class Person {
        @Id
        Long id;

        @ElementCollection
        List<String> tags;
    }

    @Entity
    @Table(name = "article")
    static class Article {
        @Id
        Long id;

        @ElementCollection
        @CollectionTable(name = "article_label", joinColumns = @JoinColumn(name = "article_fk"))
        @Column(name = "label")
        Set<Integer> labels;
    }

    @Embeddable
    static class Point {
        int x;
        int y;
    }

    @Entity
    @Table(name = "embeddable_elements")
    static class EmbeddableElements {
        @Id
        Long id;

        @ElementCollection
        List<Point> points;
    }

    @Entity
    @Table(name = "non_collection")
    static class NonCollection {
        @Id
        Long id;

        @ElementCollection
        String single;
    }
}
