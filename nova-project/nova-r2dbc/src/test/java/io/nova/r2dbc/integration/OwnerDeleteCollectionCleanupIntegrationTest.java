package io.nova.r2dbc.integration;

import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;
import io.nova.query.NativeQuery;
import io.nova.schema.SchemaInitializer;
import io.nova.schema.SimpleSchemaInitializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * owner를 hard delete할 때 owner가 소유한 컬렉션 행이 함께 정리되는지 H2 in-memory R2DBC driver로 검증한다 —
 * {@code @ElementCollection} collection table 행과 owning {@code @ManyToMany} link table 행. 정리되지 않으면
 * 고아 행이 남아(@ForeignKey 제약이 있으면 FK 위반으로 owner 삭제 자체가 실패) 데이터가 누수된다. cascade가 없는
 * M2M target 엔티티는 보존돼야 한다(링크만 끊고 상대를 지우지 않는다).
 */
class OwnerDeleteCollectionCleanupIntegrationTest {
    private H2IntegrationTestSupport support;

    @BeforeEach
    void setUp() {
        support = H2IntegrationTestSupport.create();
        SchemaInitializer schema =
                new SimpleSchemaInitializer(support.operations(), support.metadataFactory(), support.dialect());
        schema.create(Wishlist.class, Item.class).block();
    }

    private long rowCount(String table) {
        String sql = "select count(*) as cnt from " + support.dialect().quote(table);
        return support.operations()
                .queryNativeOne(NativeQuery.of(sql), row -> row.get("cnt", Long.class))
                .block();
    }

    @Test
    void deletingOwnerRemovesElementCollectionRows() {
        Wishlist wishlist = new Wishlist("w");
        wishlist.getTags().add("a");
        wishlist.getTags().add("b");
        Wishlist saved = support.operations().save(wishlist).block();
        assertEquals(2L, rowCount("wishlist_tags"));

        support.operations().delete(saved).block();

        // owner 삭제 후 collection table에 고아 행이 남지 않아야 한다.
        assertEquals(0L, rowCount("wishlist_tags"));
    }

    @Test
    void deletingOwnerRemovesManyToManyLinkRowsButKeepsTargets() {
        Item x = support.operations().save(new Item("x")).block();
        Item y = support.operations().save(new Item("y")).block();
        Wishlist wishlist = new Wishlist("w");
        wishlist.getItems().add(x);
        wishlist.getItems().add(y);
        Wishlist saved = support.operations().save(wishlist).block();
        assertEquals(2L, rowCount("wishlist_item"));

        support.operations().delete(saved).block();

        // link 행은 owner와 함께 정리되지만, cascade 없는 target 엔티티는 보존된다.
        assertEquals(0L, rowCount("wishlist_item"));
        assertEquals(2L, rowCount("item"));
    }

    @Entity
    @Table(name = "wishlist")
    public static class Wishlist {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;
        private String name;

        @ElementCollection
        private List<String> tags = new ArrayList<>();

        @ManyToMany
        @JoinTable(name = "wishlist_item",
                joinColumns = @JoinColumn(name = "wishlist_id"),
                inverseJoinColumns = @JoinColumn(name = "item_id"))
        private Set<Item> items = new LinkedHashSet<>();

        public Wishlist() {
        }

        public Wishlist(String name) {
            this.name = name;
        }

        public Long getId() {
            return id;
        }

        public List<String> getTags() {
            return tags;
        }

        public Set<Item> getItems() {
            return items;
        }
    }

    @Entity
    @Table(name = "item")
    public static class Item {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;
        private String name;

        public Item() {
        }

        public Item(String name) {
            this.name = name;
        }

        public Long getId() {
            return id;
        }
    }
}
