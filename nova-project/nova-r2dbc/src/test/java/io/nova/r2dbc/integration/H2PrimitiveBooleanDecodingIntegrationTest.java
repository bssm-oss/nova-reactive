package io.nova.r2dbc.integration;

import io.nova.r2dbc.integration.IntegrationFixtures.IdentityAccount;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * core {@code SimpleReactiveEntityOperations.wrapPrimitive}가 primitive Java 필드 타입을
 * row 디코딩 전에 boxed wrapper로 변환하는 동작을 회귀 보호한다.
 *
 * <p>cycle 9 R1 이전에는 entity가 {@code primitive boolean active} 같은 필드를 선언하면
 * {@code PersistentProperty.javaType()}이 {@code boolean.class}를 반환해서 r2dbc-h2 1.0.0이
 * {@code IllegalArgumentException: Cannot decode value of type boolean}으로 거부했다.
 * 현재는 core가 {@code row.get(name, Boolean.class)}로 boxed wrapper를 전달하므로
 * primitive 필드를 가진 entity가 round-trip 된다. wrap helper가 제거되면 이 테스트가 깨진다.
 */
class H2PrimitiveBooleanDecodingIntegrationTest {
    private H2IntegrationTestSupport support;

    @BeforeEach
    void setUp() {
        support = H2IntegrationTestSupport.create();
        support.execute(support.operations().createTableSql(IdentityAccount.class));
    }

    @Test
    void primitiveBooleanFieldRoundTripsThroughR2dbcH2() {
        // IdentityAccount.active는 primitive boolean이다 (cycle 9에서 boxed에서 되돌렸다).
        // wrapPrimitive가 boolean.class를 Boolean.class로 변환해 row.get에 전달해야
        // r2dbc-h2가 거부하지 않고 정상 디코딩한다.
        IdentityAccount original = new IdentityAccount("prim@nova.io", true);

        Mono<IdentityAccount> pipeline = support.operations().save(original)
                .flatMap(saved -> support.operations().findById(IdentityAccount.class, saved.getId()));

        StepVerifier.create(pipeline)
                .assertNext(loaded -> {
                    assertNotNull(loaded.getId());
                    assertEquals("prim@nova.io", loaded.getEmail());
                    assertTrue(loaded.isActive(), "primitive boolean true 가 round-trip 되어야 한다");
                })
                .verifyComplete();
    }

    @Test
    void primitiveBooleanFalseRoundTripsThroughR2dbcH2() {
        IdentityAccount original = new IdentityAccount("falseflag@nova.io", false);

        Mono<IdentityAccount> pipeline = support.operations().save(original)
                .flatMap(saved -> support.operations().findById(IdentityAccount.class, saved.getId()));

        StepVerifier.create(pipeline)
                .assertNext(loaded -> {
                    assertNotNull(loaded.getId());
                    assertEquals("falseflag@nova.io", loaded.getEmail());
                    assertEquals(false, loaded.isActive(), "primitive boolean false 가 round-trip 되어야 한다");
                })
                .verifyComplete();
    }
}
