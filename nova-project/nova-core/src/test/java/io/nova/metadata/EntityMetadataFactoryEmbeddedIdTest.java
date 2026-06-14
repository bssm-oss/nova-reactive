package io.nova.metadata;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code @EmbeddedId} 복합키의 메타데이터 추출(컴포넌트 → 컬럼 펼침, id 표시, 컬럼명 규칙)과
 * 거부 규칙을 보호한다.
 */
class EntityMetadataFactoryEmbeddedIdTest {
    private final EntityMetadataFactory factory = new EntityMetadataFactory(new DefaultNamingStrategy());

    @Test
    void expandsCompositeKeyComponentsAsIdColumns() {
        EntityMetadata<OrderLine> metadata = factory.getEntityMetadata(OrderLine.class);

        assertTrue(metadata.hasCompositeId());
        List<PersistentProperty> ids = metadata.idProperties();
        assertEquals(2, ids.size());
        assertTrue(ids.stream().allMatch(PersistentProperty::id));

        // @EmbeddedId 컴포넌트는 host 필드 이름 prefix 없이 컴포넌트 컬럼명으로 직접 매핑된다(JPA 규약).
        assertEquals("order_id", ids.get(0).columnName());
        assertEquals("line_no", ids.get(1).columnName());
        // property 이름은 host 필드를 포함한 dotted path다.
        assertEquals("id.orderId", ids.get(0).propertyName());
        assertEquals("id.lineNo", ids.get(1).propertyName());

        // id 컬럼은 column-mapped 뷰(SELECT/INSERT/DDL)에 포함되고, 일반 컬럼은 PK에서 제외된다.
        assertTrue(metadata.columnMappedProperties().stream().anyMatch(p -> p.columnName().equals("order_id")));
        assertTrue(metadata.columnMappedProperties().stream().anyMatch(p -> p.columnName().equals("line_no")));
        PersistentProperty quantity = metadata.findProperty("quantity").orElseThrow();
        assertFalse(quantity.id());
    }

    @Test
    void compositeKeyComponentsAreExcludedFromUpdatableAndIncludedInInsert() {
        EntityMetadata<OrderLine> metadata = factory.getEntityMetadata(OrderLine.class);

        // 복합키 컬럼은 application-assigned이므로 INSERT에 포함되지만 UPDATE SET에서는 제외된다.
        assertTrue(metadata.insertableProperties().stream().anyMatch(p -> p.columnName().equals("order_id")));
        assertTrue(metadata.insertableProperties().stream().anyMatch(p -> p.columnName().equals("line_no")));
        assertFalse(metadata.updatableProperties().stream().anyMatch(PersistentProperty::id));
    }

    @Test
    void honorsAttributeOverrideOnEmbeddedIdColumnNames() {
        EntityMetadata<OverriddenKeyEntity> metadata = factory.getEntityMetadata(OverriddenKeyEntity.class);
        List<String> columns = metadata.idProperties().stream().map(PersistentProperty::columnName).toList();
        assertTrue(columns.contains("tenant"), "columns=" + columns);
        assertTrue(columns.contains("code"), "columns=" + columns);
    }

    @Test
    void rejectsCombiningEmbeddedIdWithId() {
        assertThrows(IllegalArgumentException.class, () -> factory.getEntityMetadata(BothIdAndEmbeddedId.class));
    }

    @Test
    void rejectsGeneratedValueOnComponent() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> factory.getEntityMetadata(GeneratedComponent.class));
        assertTrue(error.getMessage().contains("GeneratedValue"));
    }

    @Test
    void rejectsNonEmbeddableKeyType() {
        assertThrows(IllegalArgumentException.class, () -> factory.getEntityMetadata(PlainKeyType.class));
    }

    // --- fixtures -----------------------------------------------------------

    @Embeddable
    static class OrderLineId {
        Long orderId;
        @Column(name = "line_no")
        Integer lineNo;
    }

    @Entity
    @Table(name = "order_line")
    static class OrderLine {
        @EmbeddedId
        OrderLineId id;
        Integer quantity;
    }

    @Embeddable
    static class TenantCodeId {
        String tenant;
        String code;
    }

    @Entity
    @Table(name = "overridden_key")
    static class OverriddenKeyEntity {
        @EmbeddedId
        @AttributeOverride(name = "tenant", column = @Column(name = "tenant"))
        @AttributeOverride(name = "code", column = @Column(name = "code"))
        TenantCodeId id;
        String label;
    }

    @Entity
    @Table(name = "both_id")
    static class BothIdAndEmbeddedId {
        @Id
        Long id;
        @EmbeddedId
        OrderLineId key;
    }

    @Embeddable
    static class GeneratedComponentId {
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        Long a;
        Long b;
    }

    @Entity
    @Table(name = "generated_component")
    static class GeneratedComponent {
        @EmbeddedId
        GeneratedComponentId id;
    }

    static class NotEmbeddable {
        Long a;
        Long b;
    }

    @Entity
    @Table(name = "plain_key")
    static class PlainKeyType {
        @EmbeddedId
        NotEmbeddable id;
    }
}
