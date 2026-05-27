package io.nova.r2dbc.integration;

import io.nova.annotation.Column;
import io.nova.annotation.Entity;
import io.nova.annotation.GeneratedValue;
import io.nova.annotation.GenerationType;
import io.nova.annotation.Id;
import io.nova.annotation.Table;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * {@code @Column(precision, scale)}로 매핑된 {@link BigDecimal} 컬럼이 실제 r2dbc-h2 driver를 통해
 * {@code save()} → {@code findById()}로 정확히 round-trip 되는지 검증한다.
 *
 * <p>이 테스트는 precision/scale 설정이 dead config가 아님을 증명한다. {@link io.nova.annotation.Column}의
 * precision/scale은 {@link io.nova.sql.AbstractSchemaGenerator}가 {@code numeric(p, s)} DDL을 emit할 때
 * 쓰이고, 읽기 경로에서는 {@link io.nova.metadata.PersistentProperty#columnType()}이 converter 없는
 * BigDecimal에 대해 {@code BigDecimal.class}를 반환하므로 driver가 numeric 컬럼을 native BigDecimal로
 * 디코딩한다. SQL 문자열 단위 테스트로는 잡히지 않는 driver 수용성을 in-memory driver로 고정한다.
 */
class BigDecimalColumnRoundTripIntegrationTest {

    @Test
    void bigDecimalColumnRoundTripsThroughSaveAndFindById() {
        H2IntegrationTestSupport support = H2IntegrationTestSupport.create();
        // schema generator가 emit하는 DDL을 그대로 실행해, @Column(precision, scale)이 만든
        // numeric(12, 2) 컬럼 정의를 실제 H2 driver가 수용하는지까지 end-to-end로 검증한다
        // (수동 DDL은 emission↔수용 불일치를 가릴 수 있다).
        support.execute(support.operations().createTableSql(PricedItem.class));

        BigDecimal amount = new BigDecimal("12345.67");
        PricedItem item = new PricedItem(amount);

        Mono<PricedItem> pipeline = support.operations().save(item)
                .flatMap(saved -> {
                    assertNotNull(saved.id, "save 이후 IDENTITY id가 채워져 있어야 한다");
                    return support.operations().findById(PricedItem.class, saved.id);
                });

        StepVerifier.create(pipeline)
                .assertNext(loaded -> {
                    assertNotNull(loaded.amount, "BigDecimal 컬럼은 numeric에서 복원되어야 한다");
                    // numeric(12, 2)는 scale 2를 보존하므로 비교는 정확히 일치해야 한다(compareTo가 아닌 equals).
                    assertEquals(0, amount.compareTo(loaded.amount),
                            "BigDecimal 값이 정확히 복원되어야 한다: expected " + amount + " but was " + loaded.amount);
                    assertEquals(2, loaded.amount.scale(),
                            "numeric(12, 2) 컬럼은 scale 2를 보존해야 한다");
                })
                .verifyComplete();
    }

    @Entity
    @Table("priced_items")
    static class PricedItem {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        Long id;

        @Column(value = "amount", precision = 12, scale = 2)
        BigDecimal amount;

        PricedItem() {
        }

        PricedItem(BigDecimal amount) {
            this.amount = amount;
        }
    }
}
