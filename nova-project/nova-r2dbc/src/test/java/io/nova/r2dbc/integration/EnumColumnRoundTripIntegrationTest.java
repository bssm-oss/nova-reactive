package io.nova.r2dbc.integration;

import io.nova.annotation.Column;
import io.nova.annotation.Entity;
import io.nova.annotation.EnumType;
import io.nova.annotation.Enumerated;
import io.nova.annotation.GeneratedValue;
import io.nova.annotation.GenerationType;
import io.nova.annotation.Id;
import io.nova.annotation.Table;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * {@code @Enumerated(STRING)}과 {@code @Enumerated(ORDINAL)} 컬럼이 실제 r2dbc-h2 driver를 통해
 * {@code save()} → {@code findById()}로 round-trip 되는지 검증한다.
 *
 * <p>회귀 가드: {@code mapRow}가 한때 enum 프로퍼티에 대해 {@code row.get(col, EnumClass)}로 디코딩을
 * 시도해 driver가 {@code "Cannot decode value of type ...Enum"}으로 거부했다. 이제는
 * {@link io.nova.metadata.PersistentProperty#columnType()}이 STRING→{@link String},
 * ORDINAL→{@link Integer} 저장 타입을 요청하고 converter가 enum으로 복원한다. SQL 문자열 단위 테스트로는
 * 잡히지 않는 driver 수용성 결함이라 in-memory driver 통합 테스트로 고정한다.
 */
class EnumColumnRoundTripIntegrationTest {

    enum Status {
        ACTIVE, SUSPENDED, CLOSED
    }

    @Test
    void enumStringAndOrdinalRoundTripThroughSaveAndFindById() {
        H2IntegrationTestSupport support = H2IntegrationTestSupport.create();
        // status_string은 STRING(varchar), status_ordinal은 ORDINAL(integer)로 저장된다. id는 IDENTITY.
        support.execute("create table \"enum_accounts\" ("
                + "\"id\" bigint generated always as identity primary key, "
                + "\"status_string\" varchar(255), "
                + "\"status_ordinal\" integer)");

        EnumAccount account = new EnumAccount(Status.SUSPENDED, Status.CLOSED);

        Mono<EnumAccount> pipeline = support.operations().save(account)
                .flatMap(saved -> {
                    assertNotNull(saved.id, "save 이후 IDENTITY id가 채워져 있어야 한다");
                    return support.operations().findById(EnumAccount.class, saved.id);
                });

        StepVerifier.create(pipeline)
                .assertNext(loaded -> {
                    assertEquals(Status.SUSPENDED, loaded.statusString,
                            "@Enumerated(STRING)은 varchar 컬럼에서 enum으로 복원되어야 한다");
                    assertEquals(Status.CLOSED, loaded.statusOrdinal,
                            "@Enumerated(ORDINAL)은 integer 컬럼에서 enum으로 복원되어야 한다");
                })
                .verifyComplete();
    }

    @Entity
    @Table("enum_accounts")
    static class EnumAccount {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        Long id;

        @Enumerated(EnumType.STRING)
        @Column("status_string")
        Status statusString;

        @Enumerated(EnumType.ORDINAL)
        @Column("status_ordinal")
        Status statusOrdinal;

        EnumAccount() {
        }

        EnumAccount(Status statusString, Status statusOrdinal) {
            this.statusString = statusString;
            this.statusOrdinal = statusOrdinal;
        }
    }
}
