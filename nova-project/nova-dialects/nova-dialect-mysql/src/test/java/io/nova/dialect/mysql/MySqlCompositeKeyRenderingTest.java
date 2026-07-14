package io.nova.dialect.mysql;

import io.nova.metadata.DefaultNamingStrategy;
import io.nova.metadata.EntityMetadata;
import io.nova.metadata.EntityMetadataFactory;
import io.nova.metadata.ForeignKeyDefinition;
import io.nova.metadata.JoinTableDefinition;
import io.nova.metadata.ManyToManyInfo;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinColumns;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 복합키(@EmbeddedId) 관계의 MySQL DDL/SQL 렌더링을 잠근다. MySQL은 backtick 식별자 quoting, 위치 기반
 * {@code ?} bind marker, ANSI 타입 토큰(bigint/integer/varchar)을 그대로 수용한다.
 */
class MySqlCompositeKeyRenderingTest {
    private final MySqlDialect dialect = new MySqlDialect();
    private final EntityMetadataFactory factory = new EntityMetadataFactory(new DefaultNamingStrategy());

    @Test
    void compositeToCompositeJoinTableUsesBacktickQuotingAndCompositePrimaryKey() {
        JoinTableDefinition def = joinTable(Item.class, "labels", Label.class);
        assertEquals(
                "create table `item_label` ("
                        + "`item_region` varchar(255) not null, "
                        + "`item_seq` bigint not null, "
                        + "`label_ns` varchar(255) not null, "
                        + "`label_code` integer not null, "
                        + "primary key (`item_region`, `item_seq`, `label_ns`, `label_code`))",
                dialect.schemaGenerator().createJoinTable(def));
    }

    @Test
    void singleToCompositeJoinTableEmitsSingleOwnerAndCompositeTargetColumns() {
        JoinTableDefinition def = joinTable(SingleOwner.class, "labels", Label.class);
        assertEquals(
                "create table `single_label` ("
                        + "`single_id` bigint not null, "
                        + "`label_ns` varchar(255) not null, "
                        + "`label_code` integer not null, "
                        + "primary key (`single_id`, `label_ns`, `label_code`))",
                dialect.schemaGenerator().createJoinTable(def));
    }

    @Test
    void compositeTargetToOneEmitsMultiColumnForeignKey() {
        EntityMetadata<Shipment> metadata = factory.getEntityMetadata(Shipment.class);
        assertEquals(
                "create table `shipments` ("
                        + "`id` bigint primary key, "
                        + "`order_region` varchar(255), "
                        + "`order_seq` bigint)",
                dialect.schemaGenerator().createTable(metadata));
    }

    @Test
    void multiColumnForeignKeyConstraintRendersBacktickedColumns() {
        ForeignKeyDefinition def = new ForeignKeyDefinition(
                "shipments", "", List.of("order_region", "order_seq"), "orders", List.of("region", "seq"));
        assertEquals(
                "alter table `shipments` add constraint `fk_shipments_order_region_order_seq`"
                        + " foreign key (`order_region`, `order_seq`) references `orders` (`region`, `seq`)",
                dialect.schemaGenerator().addForeignKey(def));
    }

    @Test
    void compositeJoinSelectRendersOrOfAndsWithPositionalMarkers() {
        JoinTableDefinition def = joinTable(Item.class, "labels", Label.class);
        io.nova.sql.SqlStatement stmt = dialect.sqlRenderer().selectJoinRowsByColumns(
                def, List.of(List.of("us", 1L), List.of("eu", 2L)));
        assertEquals(
                "select `item_region`, `item_seq`, `label_ns`, `label_code` from `item_label` where "
                        + "(`item_region` = ? and `item_seq` = ?) or (`item_region` = ? and `item_seq` = ?)",
                stmt.sql());
        assertEquals(List.of("us", 1L, "eu", 2L), stmt.bindings());
    }

    private JoinTableDefinition joinTable(Class<?> owner, String property, Class<?> target) {
        EntityMetadata<?> ownerMetadata = factory.getEntityMetadata(owner);
        EntityMetadata<?> targetMetadata = factory.getEntityMetadata(target);
        ManyToManyInfo info = ownerMetadata.findProperty(property).orElseThrow().manyToManyInfo();
        return JoinTableDefinition.of(ownerMetadata, info, targetMetadata);
    }

    // --- fixtures -----------------------------------------------------------

    @Embeddable
    static class OrderKey {
        @Column(name = "region")
        String region;
        @Column(name = "seq")
        Long seq;
    }

    @Entity
    @Table(name = "orders")
    static class CompositeOrder {
        @EmbeddedId
        OrderKey id;
        @Column(name = "note")
        String note;
    }

    @Entity
    @Table(name = "shipments")
    static class Shipment {
        @Id
        @Column(name = "id")
        Long id;

        @ManyToOne(targetEntity = CompositeOrder.class)
        @JoinColumns({
                @JoinColumn(name = "order_region", referencedColumnName = "region"),
                @JoinColumn(name = "order_seq", referencedColumnName = "seq")})
        CompositeOrder order;
    }

    @Embeddable
    static class TagKey {
        @Column(name = "ns")
        String ns;
        @Column(name = "code")
        Integer code;
    }

    @Entity
    @Table(name = "labels")
    static class Label {
        @EmbeddedId
        TagKey id;
    }

    @Entity
    @Table(name = "items")
    static class Item {
        @EmbeddedId
        OrderKey id;

        @ManyToMany
        @JoinTable(name = "item_label",
                joinColumns = {
                        @JoinColumn(name = "item_region", referencedColumnName = "region"),
                        @JoinColumn(name = "item_seq", referencedColumnName = "seq")},
                inverseJoinColumns = {
                        @JoinColumn(name = "label_ns", referencedColumnName = "ns"),
                        @JoinColumn(name = "label_code", referencedColumnName = "code")})
        List<Label> labels;
    }

    @Entity
    @Table(name = "single_owner")
    static class SingleOwner {
        @Id
        @Column(name = "id")
        Long id;

        @ManyToMany
        @JoinTable(name = "single_label",
                joinColumns = @JoinColumn(name = "single_id"),
                inverseJoinColumns = {
                        @JoinColumn(name = "label_ns", referencedColumnName = "ns"),
                        @JoinColumn(name = "label_code", referencedColumnName = "code")})
        List<Label> labels;
    }
}
