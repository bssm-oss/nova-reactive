package io.nova.dialect.postgresql;

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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 복합키(@EmbeddedId) 관계의 PostgreSQL DDL/SQL 렌더링을 잠근다. 식별자는 double-quote로 감싸고, 복합
 * join/predicate의 bind marker는 번호 기반 {@code $1,$2,...}로 위치 정합해야 한다(다른 dialect의 {@code ?}와 대비).
 * 복합 컴포넌트 타입은 저장타입 기반으로 매핑된다(UUID → varchar via UuidStringConverter).
 */
class PostgresqlCompositeKeyRenderingTest {
    private final PostgresqlDialect dialect = new PostgresqlDialect();
    private final EntityMetadataFactory factory = new EntityMetadataFactory(new DefaultNamingStrategy());

    // 1. 복합 owner ↔ 복합 target join table: quoting + 복합 PK.
    @Test
    void compositeToCompositeJoinTableUsesQuotingAndCompositePrimaryKey() {
        JoinTableDefinition def = joinTable(Item.class, "labels", Label.class);
        assertEquals(
                "create table \"item_label\" ("
                        + "\"item_region\" varchar(255) not null, "
                        + "\"item_seq\" bigint not null, "
                        + "\"label_ns\" varchar(255) not null, "
                        + "\"label_code\" integer not null, "
                        + "primary key (\"item_region\", \"item_seq\", \"label_ns\", \"label_code\"))",
                dialect.schemaGenerator().createJoinTable(def));
    }

    // 1b. 단일 owner ↔ 복합 target.
    @Test
    void singleToCompositeJoinTableEmitsSingleOwnerAndCompositeTargetColumns() {
        JoinTableDefinition def = joinTable(SingleOwner.class, "labels", Label.class);
        assertEquals(
                "create table \"single_label\" ("
                        + "\"single_id\" bigint not null, "
                        + "\"label_ns\" varchar(255) not null, "
                        + "\"label_code\" integer not null, "
                        + "primary key (\"single_id\", \"label_ns\", \"label_code\"))",
                dialect.schemaGenerator().createJoinTable(def));
    }

    // 3. 복합키 타겟 @ManyToOne: N개 FK 컬럼(quoting + 저장타입).
    @Test
    void compositeTargetToOneEmitsMultiColumnForeignKey() {
        EntityMetadata<Shipment> metadata = factory.getEntityMetadata(Shipment.class);
        assertEquals(
                "create table \"shipments\" ("
                        + "\"id\" bigint primary key, "
                        + "\"order_region\" varchar(255), "
                        + "\"order_seq\" bigint)",
                dialect.schemaGenerator().createTable(metadata));
    }

    // 5. 비-Long 복합 컴포넌트: UUID → varchar(255)(UuidStringConverter), Integer → integer.
    @Test
    void uuidAndIntegerCompositeComponentsMapToStorageTypes() {
        EntityMetadata<ChildMixed> metadata = factory.getEntityMetadata(ChildMixed.class);
        assertEquals(
                "create table \"child_mixed\" ("
                        + "\"id\" bigint primary key, "
                        + "\"p_uid\" varchar(255), "
                        + "\"p_code\" integer)",
                dialect.schemaGenerator().createTable(metadata));
    }

    // 2. 다중컬럼 복합 FK 제약: 63자 이내는 그대로, quoting 적용.
    @Test
    void multiColumnForeignKeyConstraintRendersQuotedColumns() {
        ForeignKeyDefinition def = new ForeignKeyDefinition(
                "shipments", "", List.of("order_region", "order_seq"), "orders", List.of("region", "seq"));
        assertEquals(
                "alter table \"shipments\" add constraint \"fk_shipments_order_region_order_seq\""
                        + " foreign key (\"order_region\", \"order_seq\") references \"orders\" (\"region\", \"seq\")",
                dialect.schemaGenerator().addForeignKey(def));
    }

    // 4. 다중컬럼 join predicate(OR-of-ANDs)가 PostgreSQL 번호 marker($1,$2,...)로 렌더되고 위치가 정합.
    @Test
    void compositeJoinSelectRendersOrOfAndsWithNumberedMarkers() {
        JoinTableDefinition def = joinTable(Item.class, "labels", Label.class);
        io.nova.sql.SqlStatement stmt = dialect.sqlRenderer().selectJoinRowsByColumns(
                def, List.of(List.of("us", 1L), List.of("eu", 2L)));
        assertEquals(
                "select \"item_region\", \"item_seq\", \"label_ns\", \"label_code\" from \"item_label\" where "
                        + "(\"item_region\" = $1 and \"item_seq\" = $2) or (\"item_region\" = $3 and \"item_seq\" = $4)",
                stmt.sql());
        assertEquals(List.of("us", 1L, "eu", 2L), stmt.bindings());
    }

    // 4b. 복합 link insert의 번호 marker와 binding 순서가 owner→target 순으로 정합.
    @Test
    void compositeJoinInsertBindsOwnerThenTargetColumnsWithNumberedMarkers() {
        JoinTableDefinition def = joinTable(Item.class, "labels", Label.class);
        io.nova.sql.SqlStatement stmt = dialect.sqlRenderer().insertJoinRowByColumns(
                def, List.of("us", 1L), List.of("dns", 7));
        assertEquals(
                "insert into \"item_label\" (\"item_region\", \"item_seq\", \"label_ns\", \"label_code\")"
                        + " values ($1, $2, $3, $4)",
                stmt.sql());
        assertEquals(List.of("us", 1L, "dns", 7), stmt.bindings());
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

    @Embeddable
    static class MixedKey {
        @Column(name = "uid")
        java.util.UUID uid;
        @Column(name = "code")
        Integer code;
    }

    @Entity
    @Table(name = "parent_mixed")
    static class ParentMixed {
        @EmbeddedId
        MixedKey id;
    }

    @Entity
    @Table(name = "child_mixed")
    static class ChildMixed {
        @Id
        @Column(name = "id")
        Long id;

        @ManyToOne(targetEntity = ParentMixed.class)
        @JoinColumns({
                @JoinColumn(name = "p_uid", referencedColumnName = "uid"),
                @JoinColumn(name = "p_code", referencedColumnName = "code")})
        ParentMixed parent;
    }
}
