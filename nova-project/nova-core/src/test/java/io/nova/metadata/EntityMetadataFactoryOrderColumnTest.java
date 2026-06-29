package io.nova.metadata;

import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code @OrderColumn}(정렬된 {@code @ElementCollection} List) 메타데이터 추출과 거부 규칙을 보호한다.
 */
class EntityMetadataFactoryOrderColumnTest {
    private final DefaultNamingStrategy naming = new DefaultNamingStrategy();
    private final EntityMetadataFactory factory = new EntityMetadataFactory(naming);

    @Test
    void defaultsOrderColumnNameToPropertyUnderscoreOrder() {
        EntityMetadata<Playlist> metadata = factory.getEntityMetadata(Playlist.class);
        ElementCollectionInfo info = metadata.findProperty("tracks").orElseThrow().elementCollectionInfo();

        assertTrue(info.ordered());
        assertEquals("tracks_order", info.orderColumn().columnName());
        assertFalse(info.usesSet());
    }

    @Test
    void honorsExplicitOrderColumnName() {
        EntityMetadata<NamedOrder> metadata = factory.getEntityMetadata(NamedOrder.class);
        ElementCollectionInfo info = metadata.findProperty("steps").orElseThrow().elementCollectionInfo();

        assertTrue(info.ordered());
        assertEquals("pos", info.orderColumn().columnName());
    }

    @Test
    void carriesOrderColumnOnEmbeddableElementCollection() {
        EntityMetadata<Route> metadata = factory.getEntityMetadata(Route.class);
        ElementCollectionInfo info = metadata.findProperty("legs").orElseThrow().elementCollectionInfo();

        assertTrue(info.embeddable());
        assertTrue(info.ordered());
        assertEquals("legs_order", info.orderColumn().columnName());
    }

    @Test
    void plainElementCollectionHasNoOrderColumn() {
        EntityMetadata<Unordered> metadata = factory.getEntityMetadata(Unordered.class);
        ElementCollectionInfo info = metadata.findProperty("tags").orElseThrow().elementCollectionInfo();

        assertFalse(info.ordered());
    }

    @Test
    void rejectsOrderColumnOnSet() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> factory.getEntityMetadata(SetWithOrder.class));
        assertTrue(error.getMessage().contains("@OrderColumn is only valid on an ordered List"));
    }

    @Test
    void rejectsOrderColumnTogetherWithOrderBy() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> factory.getEntityMetadata(OrderColumnAndOrderBy.class));
        assertTrue(error.getMessage().contains("cannot declare both @OrderColumn and @OrderBy"));
    }

    @Test
    void rejectsOrderColumnNameCollidingWithValueColumn() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> factory.getEntityMetadata(CollidingOrderColumn.class));
        assertTrue(error.getMessage().contains("collides with another collection table column"));
    }

    @Test
    void rejectsOrderColumnOnManyToOne() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> factory.getEntityMetadata(SingleRelationWithOrder.class));
        assertTrue(error.getMessage().contains("not on a single-valued @ManyToOne/@OneToOne"));
    }

    @Test
    void honorsOrderColumnOnOneToMany() {
        EntityMetadata<Parent> metadata = factory.getEntityMetadata(Parent.class);
        PersistentProperty children = metadata.findProperty("children").orElseThrow();

        assertTrue(children.oneToMany());
        // child 테이블의 순서 컬럼 — naming strategy 경유 기본 이름 children_order.
        assertEquals("children_order", children.oneToManyOrderColumn().columnName());
    }

    @Test
    void honorsExplicitOrderColumnNameOnOneToMany() {
        EntityMetadata<NamedParent> metadata = factory.getEntityMetadata(NamedParent.class);
        PersistentProperty children = metadata.findProperty("children").orElseThrow();

        assertEquals("child_pos", children.oneToManyOrderColumn().columnName());
    }

    @Test
    void rejectsOrderColumnOnManyToMany() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> factory.getEntityMetadata(ManyToManyWithOrder.class));
        assertTrue(error.getMessage().contains("@OrderColumn on @ManyToMany is not supported"));
    }

    // --- fixtures -----------------------------------------------------------

    @Entity
    @Table(name = "playlist")
    static class Playlist {
        @Id
        Long id;

        @ElementCollection
        @OrderColumn
        List<String> tracks;
    }

    @Entity
    @Table(name = "named_order")
    static class NamedOrder {
        @Id
        Long id;

        @ElementCollection
        @OrderColumn(name = "pos")
        List<String> steps;
    }

    @Embeddable
    static class Leg {
        String origin;
        String dest;
    }

    @Entity
    @Table(name = "route")
    static class Route {
        @Id
        Long id;

        @ElementCollection
        @OrderColumn
        List<Leg> legs;
    }

    @Entity
    @Table(name = "unordered")
    static class Unordered {
        @Id
        Long id;

        @ElementCollection
        List<String> tags;
    }

    @Entity
    @Table(name = "set_with_order")
    static class SetWithOrder {
        @Id
        Long id;

        @ElementCollection
        @OrderColumn
        Set<String> tags;
    }

    @Entity
    @Table(name = "order_column_and_order_by")
    static class OrderColumnAndOrderBy {
        @Id
        Long id;

        @ElementCollection
        @OrderColumn
        @OrderBy
        List<String> tags;
    }

    @Entity
    @Table(name = "colliding_order_column")
    static class CollidingOrderColumn {
        @Id
        Long id;

        @ElementCollection
        @OrderColumn(name = "tags")
        List<String> tags;
    }

    @Entity
    @Table(name = "single_relation_with_order")
    static class SingleRelationWithOrder {
        @Id
        Long id;

        @ManyToOne
        @JoinColumn(name = "parent_id")
        @OrderColumn
        Playlist parent;
    }

    @Entity
    @Table(name = "child")
    static class Child {
        @Id
        Long id;

        @ManyToOne
        @JoinColumn(name = "parent_id")
        Parent parent;
    }

    @Entity
    @Table(name = "parent")
    static class Parent {
        @Id
        Long id;

        @OneToMany(mappedBy = "parent", targetEntity = Child.class)
        @OrderColumn
        List<Child> children;
    }

    @Entity
    @Table(name = "named_parent")
    static class NamedParent {
        @Id
        Long id;

        @OneToMany(mappedBy = "parent", targetEntity = NamedChild.class)
        @OrderColumn(name = "child_pos")
        List<NamedChild> children;
    }

    @Entity
    @Table(name = "named_child")
    static class NamedChild {
        @Id
        Long id;

        @ManyToOne
        @JoinColumn(name = "parent_id")
        NamedParent parent;
    }

    @Entity
    @Table(name = "m2m_peer")
    static class Peer {
        @Id
        Long id;
    }

    @Entity
    @Table(name = "m2m_with_order")
    static class ManyToManyWithOrder {
        @Id
        Long id;

        @ManyToMany
        @OrderColumn
        List<Peer> peers;
    }
}
