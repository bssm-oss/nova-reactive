package io.nova.metadata;

import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code @MapsId} 파생 식별자(shared primary key) 메타데이터 인식과 거부 규칙을 보호한다.
 */
class EntityMetadataFactoryMapsIdTest {
    private final EntityMetadataFactory factory = new EntityMetadataFactory(new DefaultNamingStrategy());

    @Test
    void oneToOneMapsIdIsRecognisedAsDerivedIdentifier() {
        EntityMetadata<Detail> detail = factory.getEntityMetadata(Detail.class);
        PersistentProperty master = detail.findProperty("master").orElseThrow();

        assertTrue(master.mapsId(), "@MapsId owning @OneToOne은 파생 식별자 마커를 가져야 한다");
        assertEquals("", master.mapsIdValue(), "단순 @MapsId는 빈 value를 가진다");
        assertTrue(master.manyToOne(), "owning @OneToOne은 단건 참조로 모델링된다");
        assertTrue(detail.mapsIdProperty().isPresent(), "metadata는 @MapsId property를 노출해야 한다");
        assertEquals("master", detail.mapsIdProperty().orElseThrow().propertyName());
    }

    @Test
    void manyToOneMapsIdIsRecognisedAsDerivedIdentifier() {
        EntityMetadata<DetailM2O> detail = factory.getEntityMetadata(DetailM2O.class);
        PersistentProperty master = detail.findProperty("master").orElseThrow();

        assertTrue(master.mapsId());
        assertTrue(detail.mapsIdProperty().isPresent());
    }

    @Test
    void nonMapsIdRelationHasNoMarker() {
        EntityMetadata<PlainDetail> detail = factory.getEntityMetadata(PlainDetail.class);
        assertFalse(detail.findProperty("master").orElseThrow().mapsId());
        assertTrue(detail.mapsIdProperty().isEmpty());
    }

    @Test
    void rejectsMapsIdWithValueReferencingCompositeComponent() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> factory.getEntityMetadata(MapsIdWithValue.class));
        assertTrue(error.getMessage().contains("@MapsId"));
        assertTrue(error.getMessage().contains("composite"));
    }

    @Test
    void recognisesMapsIdComponentDerivingCompositeEmbeddedIdComponent() {
        EntityMetadata<OrderLine> line = factory.getEntityMetadata(OrderLine.class);
        PersistentProperty order = line.findProperty("order").orElseThrow();

        assertTrue(order.mapsId(), "@MapsId(\"component\") 관계는 파생 식별자 마커를 가져야 한다");
        assertEquals("orderRef", order.mapsIdValue(),
                "복합키 컴포넌트 파생은 컴포넌트 이름을 마커로 보관해야 한다");
        assertEquals(1, line.mapsIdProperties().size());
        assertEquals("order", line.mapsIdProperties().get(0).propertyName());
        assertTrue(line.hasCompositeId(), "owner는 @EmbeddedId 복합키여야 한다");
    }

    @Test
    void rejectsMapsIdComponentThatDoesNotMatchAnyIdComponent() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> factory.getEntityMetadata(OrderLineBadComponent.class));
        assertTrue(error.getMessage().contains("@MapsId"), error.getMessage());
        assertTrue(error.getMessage().contains("does not match"), error.getMessage());
    }

    @Test
    void rejectsMapsIdCombinedWithGeneratedValue() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> factory.getEntityMetadata(MapsIdGeneratedId.class));
        assertTrue(error.getMessage().contains("@GeneratedValue"));
    }

    @Test
    void rejectsMapsIdOnInverseOneToOne() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> factory.getEntityMetadata(MapsIdOnInverse.class));
        assertTrue(error.getMessage().contains("@MapsId"));
        assertTrue(error.getMessage().contains("owning"));
    }

    @Test
    void rejectsMapsIdOnManyToMany() {
        assertThrows(IllegalStateException.class, () -> factory.getEntityMetadata(MapsIdOnManyToMany.class));
    }

    // --- fixtures -----------------------------------------------------------

    @Entity
    @Table(name = "maps_id_master")
    static class Master {
        @Id
        Long id;
    }

    @Entity
    @Table(name = "maps_id_detail")
    static class Detail {
        @Id
        Long id;

        @OneToOne
        @MapsId
        @JoinColumn(name = "master_id")
        Master master;
    }

    @Entity
    @Table(name = "maps_id_detail_m2o")
    static class DetailM2O {
        @Id
        Long id;

        @ManyToOne
        @MapsId
        @JoinColumn(name = "master_id")
        Master master;
    }

    @Entity
    @Table(name = "plain_detail")
    static class PlainDetail {
        @Id
        Long id;

        @OneToOne
        @JoinColumn(name = "master_id")
        Master master;
    }

    @Entity
    @Table(name = "maps_id_with_value")
    static class MapsIdWithValue {
        @Id
        Long id;

        @OneToOne
        @MapsId("id")
        @JoinColumn(name = "master_id")
        Master master;
    }

    @Entity
    @Table(name = "maps_id_generated")
    static class MapsIdGeneratedId {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        Long id;

        @OneToOne
        @MapsId
        @JoinColumn(name = "master_id")
        Master master;
    }

    @Entity
    @Table(name = "maps_id_inverse")
    static class MapsIdOnInverse {
        @Id
        Long id;

        @OneToOne(mappedBy = "owner", targetEntity = InverseOwner.class)
        @MapsId
        InverseOwner owner;
    }

    @Entity
    @Table(name = "maps_id_inverse_owner")
    static class InverseOwner {
        @Id
        Long id;

        @OneToOne
        @JoinColumn(name = "owner_target_id")
        MapsIdOnInverse owner;
    }

    @Entity
    @Table(name = "maps_id_m2m")
    static class MapsIdOnManyToMany {
        @Id
        Long id;

        @ManyToMany(targetEntity = Master.class)
        @MapsId
        List<Master> masters;
    }

    // --- composite-component @MapsId fixtures -------------------------------

    @Embeddable
    static class OrderLineId {
        Long orderRef;
        Long lineNo;
    }

    @Entity
    @Table(name = "maps_id_order_line")
    static class OrderLine {
        @EmbeddedId
        OrderLineId id;

        // 복합 @Id의 "orderRef" 컴포넌트를 단일키 Master의 PK에서 파생한다. FK 컬럼(order_id)과
        // 컴포넌트 컬럼(order_ref)은 Nova 모델상 별도 컬럼으로 emit된다.
        @ManyToOne
        @MapsId("orderRef")
        @JoinColumn(name = "order_id")
        Master order;
    }

    @Entity
    @Table(name = "maps_id_order_line_bad")
    static class OrderLineBadComponent {
        @EmbeddedId
        OrderLineId id;

        @ManyToOne
        @MapsId("nope")
        @JoinColumn(name = "order_id")
        Master order;
    }
}
