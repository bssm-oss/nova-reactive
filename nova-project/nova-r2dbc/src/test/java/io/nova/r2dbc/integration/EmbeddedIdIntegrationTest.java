package io.nova.r2dbc.integration;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import io.nova.schema.SchemaInitializer;
import io.nova.schema.SimpleSchemaInitializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * {@code @EmbeddedId} 복합키 entity가 H2 in-memory R2DBC driver와 end-to-end로 round-trip 되는지 검증한다.
 * 복합키는 application-assigned이라 {@code save()}가 존재 여부를 SELECT로 확인해 insert/update를 가른다.
 */
class EmbeddedIdIntegrationTest {
    private H2IntegrationTestSupport support;

    @BeforeEach
    void setUp() {
        support = H2IntegrationTestSupport.create();
        SchemaInitializer schema =
                new SimpleSchemaInitializer(support.operations(), support.metadataFactory(), support.dialect());
        schema.create(OrderLine.class).block();
    }

    @Test
    void insertsThenFindsByCompositeKey() {
        OrderLineId key = new OrderLineId(100L, 1);
        Mono<OrderLine> pipeline = support.operations().save(new OrderLine(key, 7))
                .then(support.operations().findById(OrderLine.class, new OrderLineId(100L, 1)));

        StepVerifier.create(pipeline)
                .assertNext(found -> {
                    assertNotNull(found.getId());
                    assertEquals(100L, found.getId().getOrderId());
                    assertEquals(1, found.getId().getLineNo());
                    assertEquals(7, found.getQuantity());
                })
                .verifyComplete();
    }

    @Test
    void secondSaveUpdatesExistingRowInsteadOfInserting() {
        OrderLineId key = new OrderLineId(200L, 2);
        Mono<OrderLine> pipeline = support.operations().save(new OrderLine(key, 1))
                .then(support.operations().save(new OrderLine(new OrderLineId(200L, 2), 42)))
                .then(support.operations().findById(OrderLine.class, new OrderLineId(200L, 2)));

        StepVerifier.create(pipeline)
                .assertNext(found -> assertEquals(42, found.getQuantity(),
                        "두 번째 save는 같은 복합키 row를 UPDATE 해야 한다"))
                .verifyComplete();

        // 단 한 행만 존재해야 한다(두 번째 save가 새 row를 INSERT 하지 않았음).
        StepVerifier.create(support.operations().count(OrderLine.class, io.nova.query.QuerySpec.empty()))
                .expectNext(1L)
                .verifyComplete();
    }

    @Test
    void deletesByCompositeKey() {
        Mono<Long> pipeline = support.operations().save(new OrderLine(new OrderLineId(300L, 3), 5))
                .then(support.operations().delete(new OrderLine(new OrderLineId(300L, 3), 5)))
                .then(support.operations().count(OrderLine.class, io.nova.query.QuerySpec.empty()));

        StepVerifier.create(pipeline)
                .expectNext(0L)
                .verifyComplete();
    }

    @Embeddable
    public static class OrderLineId {
        @Column(name = "order_id")
        private Long orderId;
        @Column(name = "line_no")
        private Integer lineNo;

        public OrderLineId() {
        }

        public OrderLineId(Long orderId, Integer lineNo) {
            this.orderId = orderId;
            this.lineNo = lineNo;
        }

        public Long getOrderId() {
            return orderId;
        }

        public Integer getLineNo() {
            return lineNo;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof OrderLineId that)) {
                return false;
            }
            return Objects.equals(orderId, that.orderId) && Objects.equals(lineNo, that.lineNo);
        }

        @Override
        public int hashCode() {
            return Objects.hash(orderId, lineNo);
        }
    }

    @Entity
    @Table(name = "order_line")
    public static class OrderLine {
        @EmbeddedId
        private OrderLineId id;
        private Integer quantity;

        public OrderLine() {
        }

        public OrderLine(OrderLineId id, Integer quantity) {
            this.id = id;
            this.quantity = quantity;
        }

        public OrderLineId getId() {
            return id;
        }

        public Integer getQuantity() {
            return quantity;
        }
    }
}
