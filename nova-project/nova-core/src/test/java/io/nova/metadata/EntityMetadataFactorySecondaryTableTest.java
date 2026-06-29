package io.nova.metadata;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrimaryKeyJoinColumn;
import jakarta.persistence.SecondaryTable;
import jakarta.persistence.Table;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code @SecondaryTable}/{@code @PrimaryKeyJoinColumn} 메타데이터 추출과 fail-fast 거부 규칙을 보호한다.
 */
class EntityMetadataFactorySecondaryTableTest {
    private final EntityMetadataFactory factory = new EntityMetadataFactory(new DefaultNamingStrategy());

    @Test
    void routesColumnsToSecondaryTableWithDefaultPkJoin() {
        EntityMetadata<User> metadata = factory.getEntityMetadata(User.class);

        assertTrue(metadata.hasSecondaryTables());
        assertEquals(1, metadata.secondaryTables().size());
        SecondaryTableInfo info = metadata.secondaryTables().get(0);
        assertEquals("user_details", info.tableName());
        // pkJoinColumn / referencedColumn 모두 primary @Id 컬럼 이름으로 기본 설정된다.
        assertEquals("id", info.pkJoinColumn());
        assertEquals("id", info.primaryKeyColumn());

        // bio/avatar는 보조 테이블로, name은 primary로 라우팅된다.
        PersistentProperty bio = metadata.findProperty("bio").orElseThrow();
        assertTrue(bio.secondary());
        assertEquals("user_details", bio.secondaryTableName());
        assertFalse(metadata.findProperty("name").orElseThrow().secondary());

        List<String> primaryColumns = metadata.primaryColumnMappedProperties().stream()
                .map(PersistentProperty::columnName).toList();
        assertTrue(primaryColumns.contains("id"));
        assertTrue(primaryColumns.contains("name"));
        assertFalse(primaryColumns.contains("bio"));
        assertFalse(primaryColumns.contains("avatar"));

        List<String> secondaryColumns = metadata.secondaryColumnMappedProperties(info).stream()
                .map(PersistentProperty::columnName).toList();
        assertEquals(List.of("bio", "avatar"), secondaryColumns);

        // 전체 컬럼 매핑 뷰에는 primary + secondary 컬럼이 모두 포함된다(row 디코딩이 둘 다 채운다).
        List<String> all = metadata.columnMappedProperties().stream()
                .map(PersistentProperty::columnName).toList();
        assertTrue(all.containsAll(List.of("id", "name", "bio", "avatar")));
    }

    @Test
    void honorsPrimaryKeyJoinColumnOverride() {
        EntityMetadata<Account> metadata = factory.getEntityMetadata(Account.class);
        SecondaryTableInfo info = metadata.secondaryTables().get(0);
        assertEquals("account_audit", info.tableName());
        assertEquals("account_fk", info.pkJoinColumn());
        assertEquals("id", info.primaryKeyColumn());
    }

    @Test
    void supportsMultipleSecondaryTables() {
        EntityMetadata<Product> metadata = factory.getEntityMetadata(Product.class);
        assertEquals(2, metadata.secondaryTables().size());
        List<String> names = metadata.secondaryTables().stream().map(SecondaryTableInfo::tableName).toList();
        assertTrue(names.contains("product_pricing"));
        assertTrue(names.contains("product_seo"));
        assertEquals("price", metadata.secondaryColumnMappedProperties(
                metadata.secondaryTables().stream().filter(t -> t.tableName().equals("product_pricing"))
                        .findFirst().orElseThrow()).get(0).columnName());
    }

    @Test
    void rejectsColumnTableReferencingUndeclaredSecondaryTable() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class, () -> factory.getEntityMetadata(WrongTableRef.class));
        assertTrue(exception.getMessage().contains("undeclared @SecondaryTable"));
    }

    @Test
    void rejectsColumnTableWithoutAnySecondaryTable() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class, () -> factory.getEntityMetadata(NoSecondaryDeclared.class));
        assertTrue(exception.getMessage().contains("not declared via @SecondaryTable"));
    }

    @Test
    void rejectsCompositeIdOwnerWithSecondaryTable() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class, () -> factory.getEntityMetadata(CompositeKeyed.class));
        assertTrue(exception.getMessage().contains("composite id"));
    }

    @Test
    void rejectsGeneratedIdRoutedToSecondaryTable() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class, () -> factory.getEntityMetadata(IdRoutedToSecondary.class));
        // @Id 이면서 @GeneratedValue인 컬럼을 보조 테이블로 라우팅 → 거부.
        assertTrue(exception.getMessage().contains("@SecondaryTable"));
    }

    @Test
    void rejectsMultiplePkJoinColumns() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class, () -> factory.getEntityMetadata(MultiPkJoin.class));
        assertTrue(exception.getMessage().contains("composite keys"));
    }

    @Entity
    @Table(name = "users")
    @SecondaryTable(name = "user_details")
    static class User {
        @Id
        private Long id;
        private String name;
        @Column(table = "user_details")
        private String bio;
        @Column(table = "user_details")
        private String avatar;
    }

    @Entity
    @Table(name = "account")
    @SecondaryTable(name = "account_audit",
            pkJoinColumns = @PrimaryKeyJoinColumn(name = "account_fk", referencedColumnName = "id"))
    static class Account {
        @Id
        private Long id;
        @Column(table = "account_audit")
        private String note;
    }

    @Entity
    @Table(name = "product")
    @SecondaryTable(name = "product_pricing")
    @SecondaryTable(name = "product_seo")
    static class Product {
        @Id
        private Long id;
        private String name;
        @Column(table = "product_pricing")
        private Long price;
        @Column(table = "product_seo")
        private String slug;
    }

    @Entity
    @Table(name = "wrong_ref")
    @SecondaryTable(name = "declared_table")
    static class WrongTableRef {
        @Id
        private Long id;
        @Column(table = "other_table")
        private String value;
    }

    @Entity
    @Table(name = "no_secondary")
    static class NoSecondaryDeclared {
        @Id
        private Long id;
        @Column(table = "ghost_table")
        private String value;
    }

    @Entity
    @Table(name = "composite_keyed")
    @SecondaryTable(name = "composite_keyed_extra")
    static class CompositeKeyed {
        @EmbeddedId
        private Key key;
        @Column(table = "composite_keyed_extra")
        private String detail;

        @Embeddable
        static class Key {
            private Long a;
            private Long b;
        }
    }

    @Entity
    @Table(name = "id_routed")
    @SecondaryTable(name = "id_routed_extra")
    static class IdRoutedToSecondary {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        @Column(table = "id_routed_extra")
        private Long id;
        private String name;
    }

    @Entity
    @Table(name = "multi_pk_join")
    @SecondaryTable(name = "multi_pk_join_extra",
            pkJoinColumns = {
                    @PrimaryKeyJoinColumn(name = "a_fk"),
                    @PrimaryKeyJoinColumn(name = "b_fk")
            })
    static class MultiPkJoin {
        @Id
        private Long id;
        @Column(table = "multi_pk_join_extra")
        private String detail;
    }
}
