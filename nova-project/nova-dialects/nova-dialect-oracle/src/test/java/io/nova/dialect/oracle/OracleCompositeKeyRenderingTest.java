package io.nova.dialect.oracle;

import io.nova.metadata.DefaultNamingStrategy;
import io.nova.metadata.EntityMetadata;
import io.nova.metadata.EntityMetadataFactory;
import io.nova.metadata.ForeignKeyDefinition;
import io.nova.metadata.JoinTableDefinition;
import io.nova.metadata.ManyToManyInfo;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
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
 * 복합키(@EmbeddedId) 관계의 Oracle DDL/SQL 렌더링을 잠근다. Oracle은 {@code bigint}/{@code boolean} 타입이
 * 없으므로 복합 FK/link 컬럼도 스칼라 컬럼과 동일한 Oracle 네이티브 토큰({@code number(19)}/{@code varchar2})으로
 * emit돼야 한다 — base {@link io.nova.sql.AbstractSchemaGenerator}의 ANSI 토큰({@code bigint})을 그대로 쓰면
 * ORA-00902로 깨진다. 식별자는 double-quote, bind marker는 위치 기반 {@code ?}, 자동 FK 제약명은 30자로 bound된다.
 */
class OracleCompositeKeyRenderingTest {
    private final OracleDialect dialect = new OracleDialect();
    private final EntityMetadataFactory factory = new EntityMetadataFactory(new DefaultNamingStrategy());

    // 1. 복합 owner ↔ 복합 target join table: N+M FK 컬럼 + 전체 복합 PK, Oracle 타입 토큰/quoting.
    @Test
    void compositeToCompositeJoinTableUsesOracleTypesAndCompositePrimaryKey() {
        JoinTableDefinition def = joinTable(Item.class, "labels", Label.class);
        assertEquals(
                "create table \"item_label\" ("
                        + "\"item_region\" varchar2(255) not null, "
                        + "\"item_seq\" number(19) not null, "
                        + "\"label_ns\" varchar2(255) not null, "
                        + "\"label_code\" number(10) not null, "
                        + "primary key (\"item_region\", \"item_seq\", \"label_ns\", \"label_code\"))",
                dialect.schemaGenerator().createJoinTable(def));
    }

    // 1b. 단일 owner ↔ 복합 target: owner FK 1개(number(19)) + target FK 2개.
    @Test
    void singleToCompositeJoinTableEmitsSingleOwnerAndCompositeTargetColumns() {
        JoinTableDefinition def = joinTable(SingleOwner.class, "labels", Label.class);
        assertEquals(
                "create table \"single_label\" ("
                        + "\"single_id\" number(19) not null, "
                        + "\"label_ns\" varchar2(255) not null, "
                        + "\"label_code\" number(10) not null, "
                        + "primary key (\"single_id\", \"label_ns\", \"label_code\"))",
                dialect.schemaGenerator().createJoinTable(def));
    }

    // 3. 복합키 타겟 @ManyToOne: N개 FK 컬럼이 Oracle 타입으로 emit(number(19) — bigint 아님).
    @Test
    void compositeTargetToOneEmitsMultiColumnForeignKeyInOracleTypes() {
        EntityMetadata<Shipment> metadata = factory.getEntityMetadata(Shipment.class);
        assertEquals(
                "create table \"shipments\" ("
                        + "\"id\" number(19) primary key, "
                        + "\"order_region\" varchar2(255), "
                        + "\"order_seq\" number(19))",
                dialect.schemaGenerator().createTable(metadata));
    }

    // 5. 비-Long 복합 컴포넌트(Integer)도 스칼라와 동일하게 number(10)으로 매핑되는지 명시적으로 잠근다.
    @Test
    void integerCompositeComponentMapsToNumber10NotAnsiInteger() {
        JoinTableDefinition def = joinTable(Item.class, "labels", Label.class);
        String ddl = dialect.schemaGenerator().createJoinTable(def);
        assertTrue(ddl.contains("\"label_code\" number(10)"), ddl);
        // ANSI integer/bigint 토큰이 새어나오지 않아야 한다.
        assertTrue(!ddl.contains(" integer") && !ddl.contains(" bigint"), ddl);
    }

    // 2. 다중컬럼 복합 FK 제약: 30자 초과 자동 이름이 결정적으로 bound되고 멱등(존재 체크 키 == 발행 이름)해야 한다.
    @Test
    void multiColumnForeignKeyConstraintBoundsNameToOracle30LimitIdempotently() {
        ForeignKeyDefinition def = new ForeignKeyDefinition(
                "shipment_dispatch_reconciliation", "",
                List.of("originating_order_region", "originating_order_sequence"),
                "orders", List.of("region", "seq"));

        String name = dialect.schemaGenerator().foreignKeyName(def);
        assertTrue(name.length() <= 30, "bounded to Oracle 30-char limit, got " + name.length() + ": " + name);
        // 멱등: 두 번째 해석도 동일 이름(카탈로그 존재 체크와 실제 DDL이 일치).
        assertEquals(name, dialect.schemaGenerator().foreignKeyName(def));
        String ddl = dialect.schemaGenerator().addForeignKey(def);
        assertEquals(
                "alter table \"shipment_dispatch_reconciliation\" add constraint \"" + name + "\""
                        + " foreign key (\"originating_order_region\", \"originating_order_sequence\")"
                        + " references \"orders\" (\"region\", \"seq\")",
                ddl);
    }

    // 4. 다중컬럼 join 조회 predicate(OR-of-ANDs)가 Oracle 위치 marker(?)로 정확히 렌더되고 위치가 정합.
    @Test
    void compositeJoinSelectRendersOrOfAndsWithPositionalMarkers() {
        JoinTableDefinition def = joinTable(Item.class, "labels", Label.class);
        io.nova.sql.SqlStatement stmt = dialect.sqlRenderer().selectJoinRowsByColumns(
                def, List.of(List.of("us", 1L), List.of("eu", 2L)));
        assertEquals(
                "select \"item_region\", \"item_seq\", \"label_ns\", \"label_code\" from \"item_label\" where "
                        + "(\"item_region\" = ? and \"item_seq\" = ?) or (\"item_region\" = ? and \"item_seq\" = ?)",
                stmt.sql());
        assertEquals(List.of("us", 1L, "eu", 2L), stmt.bindings());
    }

    // 4b. 복합 link insert의 위치 marker와 binding 순서가 owner→target 컬럼 순서로 정합.
    @Test
    void compositeJoinInsertBindsOwnerThenTargetColumnsInOrder() {
        JoinTableDefinition def = joinTable(Item.class, "labels", Label.class);
        io.nova.sql.SqlStatement stmt = dialect.sqlRenderer().insertJoinRowByColumns(
                def, List.of("us", 1L), List.of("dns", 7));
        assertEquals(
                "insert into \"item_label\" (\"item_region\", \"item_seq\", \"label_ns\", \"label_code\")"
                        + " values (?, ?, ?, ?)",
                stmt.sql());
        assertEquals(List.of("us", 1L, "dns", 7), stmt.bindings());
    }

    // element-path token-leak 가드: @ElementCollection collection 테이블의 owner-FK/element 컬럼도 같은 fix
    // (elementColumnType override)로 Oracle 타입을 써야 한다. owner Long → number(19), 원소 Long → number(19),
    // 원소 String → varchar2(255). pre-fix면 owner/element가 bigint로 새어 실패한다(teeth).
    @Test
    void elementCollectionTablesUseOracleTypesNotAnsiTokens() {
        String longDdl = collectionTableDdl(Warehouse.class, "binNumbers");
        assertEquals(
                "create table \"warehouse_bin_numbers\" ("
                        + "\"warehouse_id\" number(19) not null, \"bin_numbers\" number(19))",
                longDdl);

        String stringDdl = collectionTableDdl(Warehouse.class, "zoneCodes");
        assertEquals(
                "create table \"warehouse_zone_codes\" ("
                        + "\"warehouse_id\" number(19) not null, \"zone_codes\" varchar2(255))",
                stringDdl);

        // ANSI 토큰(bigint/integer/ANSI varchar())이 owner-FK/element 어느 쪽에도 새지 않아야 한다.
        for (String ddl : List.of(longDdl, stringDdl)) {
            assertTrue(!ddl.contains(" bigint") && !ddl.contains(" integer") && !ddl.contains(" varchar("), ddl);
        }
    }

    private String collectionTableDdl(Class<?> owner, String property) {
        io.nova.metadata.ElementCollectionInfo info = factory.getEntityMetadata(owner)
                .findProperty(property).orElseThrow().elementCollectionInfo();
        io.nova.metadata.CollectionTableDefinition definition = info.toCollectionTableDefinition(Long.class);
        return dialect.schemaGenerator().createCollectionTable(definition);
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

    @Entity
    @Table(name = "warehouse")
    static class Warehouse {
        @Id
        @Column(name = "id")
        Long id;

        @ElementCollection
        List<Long> binNumbers;

        @ElementCollection
        List<String> zoneCodes;
    }
}
