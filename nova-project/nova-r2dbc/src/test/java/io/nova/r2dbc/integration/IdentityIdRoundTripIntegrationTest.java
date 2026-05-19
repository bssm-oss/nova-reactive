package io.nova.r2dbc.integration;

import io.nova.r2dbc.integration.IntegrationFixtures.IdentityAccount;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code @GeneratedValue(IDENTITY)} entity가 실제 H2에서 RETURNING 절을 통해 키를 회수하고,
 * 다시 {@code findById}로 round-trip 되는지 검증한다.
 */
class IdentityIdRoundTripIntegrationTest {
    private H2IntegrationTestSupport support;

    @BeforeEach
    void setUp() {
        support = H2IntegrationTestSupport.create();
        // dialect의 schema generator가 만드는 DDL을 그대로 사용해, dialect identity 컬럼 문법이
        // 실제 H2에서 거부되지 않는지도 함께 검증한다.
        String createTableSql = support.operations().createTableSql(IdentityAccount.class);
        support.execute(createTableSql);
    }

    @Test
    void savesIdentityAccountAndAssignsGeneratedKey() {
        IdentityAccount account = new IdentityAccount("first@nova.io", true);

        StepVerifier.create(support.operations().save(account))
                .assertNext(saved -> {
                    assertNotNull(saved.getId(), "RETURNING 절로 회수한 IDENTITY 키가 entity에 주입되어야 한다");
                    assertTrue(saved.getId() > 0L, "H2 IDENTITY 값은 양수여야 한다");
                    assertEquals("first@nova.io", saved.getEmail());
                    assertTrue(saved.isActive());
                })
                .verifyComplete();
    }

    @Test
    void roundTripsSavedIdentityAccountByFindById() {
        IdentityAccount account = new IdentityAccount("round@nova.io", false);

        // save → 동일 entity instance에 주입된 id로 findById 하는 pipeline을 한 reactive 흐름으로 묶는다.
        Mono<IdentityAccount> pipeline = support.operations().save(account)
                .flatMap(saved -> {
                    assertNotNull(saved.getId(), "save 이후 entity.id가 채워져 있어야 한다");
                    return support.operations().findById(IdentityAccount.class, saved.getId());
                });

        StepVerifier.create(pipeline)
                .assertNext(loaded -> {
                    assertNotNull(loaded.getId());
                    assertEquals(account.getId(), loaded.getId());
                    assertEquals("round@nova.io", loaded.getEmail());
                    assertEquals(false, loaded.isActive());
                })
                .verifyComplete();
    }

    @Test
    void assignsIncrementalKeysAcrossMultipleSaves() {
        IdentityAccount first = new IdentityAccount("a@nova.io", true);
        IdentityAccount second = new IdentityAccount("b@nova.io", true);
        IdentityAccount third = new IdentityAccount("c@nova.io", true);

        AtomicReference<Long> firstId = new AtomicReference<>();
        AtomicReference<Long> secondId = new AtomicReference<>();
        AtomicReference<Long> thirdId = new AtomicReference<>();

        Mono<Void> pipeline = support.operations().save(first)
                .doOnNext(saved -> firstId.set(saved.getId()))
                .then(support.operations().save(second))
                .doOnNext(saved -> secondId.set(saved.getId()))
                .then(support.operations().save(third))
                .doOnNext(saved -> thirdId.set(saved.getId()))
                .then();

        StepVerifier.create(pipeline).verifyComplete();

        assertNotNull(firstId.get());
        assertNotNull(secondId.get());
        assertNotNull(thirdId.get());
        assertTrue(firstId.get() < secondId.get(),
                "IDENTITY는 단조 증가해야 한다 (" + firstId.get() + " < " + secondId.get() + ")");
        assertTrue(secondId.get() < thirdId.get(),
                "IDENTITY는 단조 증가해야 한다 (" + secondId.get() + " < " + thirdId.get() + ")");
    }
}
