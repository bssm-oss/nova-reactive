package io.nova.metadata;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Embedded;
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
    void expandsEmbeddableElementIntoMultipleColumns() {
        EntityMetadata<EmbeddableElements> metadata = factory.getEntityMetadata(EmbeddableElements.class);
        PersistentProperty points = metadata.findProperty("points").orElseThrow();

        assertTrue(points.elementCollection());
        ElementCollectionInfo info = points.elementCollectionInfo();
        assertTrue(info.embeddable());
        assertEquals(Point.class, info.valueType());
        // 펼친 컬럼: x, y (선언 순서 보존, naming strategy 적용).
        assertEquals(2, info.embeddableColumns().size());
        assertEquals(List.of(naming.columnName("x"), naming.columnName("y")),
                info.embeddableColumns().stream().map(ElementCollectionInfo.EmbeddableColumn::columnName).toList());
        assertEquals(Integer.class, info.embeddableColumns().get(0).columnType());
        // 여전히 컬럼 없는 marker라 owner 테이블 컬럼에 섞이지 않는다.
        assertFalse(metadata.columnMappedProperties().stream().anyMatch(p -> p.propertyName().equals("points")));
    }

    @Test
    void honorsAttributeOverrideAndColumnOnEmbeddableElement() {
        EntityMetadata<Trip> metadata = factory.getEntityMetadata(Trip.class);
        ElementCollectionInfo info = metadata.findProperty("legs").orElseThrow().elementCollectionInfo();
        assertTrue(info.embeddable());
        // @Column(name="from_city") on the component, @AttributeOverride(name="to", column="dest") on the field.
        assertEquals(List.of("from_city", "dest"),
                info.embeddableColumns().stream().map(ElementCollectionInfo.EmbeddableColumn::columnName).toList());
    }

    @Test
    void rejectsEmbeddableElementWithDuplicateColumnNames() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> factory.getEntityMetadata(DuplicateColumns.class));
        assertTrue(error.getMessage().contains("duplicate column"));
    }

    @Test
    void rejectsNestedEmbeddedInEmbeddableElement() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> factory.getEntityMetadata(NestedEmbeddedElement.class));
        assertTrue(error.getMessage().contains("nested @Embedded"));
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

    @Embeddable
    static class Leg {
        @Column(name = "from_city")
        String from;
        String to;
    }

    @Entity
    @Table(name = "trip")
    static class Trip {
        @Id
        Long id;

        @ElementCollection
        @AttributeOverride(name = "to", column = @Column(name = "dest"))
        List<Leg> legs;
    }

    @Embeddable
    static class Clashing {
        @Column(name = "same")
        String a;
        @Column(name = "same")
        String b;
    }

    @Entity
    @Table(name = "duplicate_columns")
    static class DuplicateColumns {
        @Id
        Long id;

        @ElementCollection
        List<Clashing> values;
    }

    @Embeddable
    static class Outer {
        @Embedded
        Point inner;
    }

    @Entity
    @Table(name = "nested_embedded_element")
    static class NestedEmbeddedElement {
        @Id
        Long id;

        @ElementCollection
        List<Outer> outers;
    }
}
