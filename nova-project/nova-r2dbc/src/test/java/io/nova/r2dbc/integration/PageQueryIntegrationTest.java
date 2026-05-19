package io.nova.r2dbc.integration;

import io.nova.query.Pageable;
import io.nova.query.QuerySpec;
import io.nova.r2dbc.integration.IntegrationFixtures.IdentityAccount;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Production {@link io.nova.dialect.h2.H2Dialect}와 r2dbc-h2 driver를 거치는 paged
 * {@code findAll}/{@code findSlice} 동작을 end-to-end로 검증한다. dialect SQL 렌더링,
 * R2DBC bind marker, LIMIT/OFFSET 절이 실제 driver에 거부되지 않는지 함께 회귀 보호한다.
 *
 * <p>cycle 9에서 production {@link io.nova.dialect.h2.H2Dialect}가 production-grade로 정착했으므로
 * core unit test의 SQL 문자열 검증만으로 잡히지 않는 driver 수용성 문제(예: LIMIT 바인딩 타입)는
 * 본 integration test가 1차로 잡는다.
 */
class PageQueryIntegrationTest {
    private H2IntegrationTestSupport support;

    @BeforeEach
    void setUp() {
        support = H2IntegrationTestSupport.create();
        // dialect의 DDL 그대로 사용 — production 경로와 동일하게 IDENTITY 컬럼이 만들어진다.
        support.execute(support.operations().createTableSql(IdentityAccount.class));
        // 10건 insert: id는 IDENTITY로 자동 채워진다.
        Flux<IdentityAccount> inserts = Flux.range(1, 10)
                .map(i -> new IdentityAccount("user" + i + "@nova.io", i % 2 == 0))
                .concatMap(support.operations()::save);
        StepVerifier.create(inserts.then()).verifyComplete();
    }

    @Test
    void findAllByPageableReturnsPageWithTotalCountAndHasNext() {
        // limit 3, offset 6 → page index 2, total 10 → totalPages 4, hasNext=true
        Pageable pageable = Pageable.of(3, 6L);

        StepVerifier.create(support.operations().findAll(IdentityAccount.class, QuerySpec.empty(), pageable))
                .assertNext(page -> {
                    assertEquals(3, page.content().size(),
                            "page content는 limit과 일치해야 한다");
                    assertEquals(10L, page.totalElements(),
                            "totalElements는 LIMIT 무관한 전체 row count여야 한다");
                    assertEquals(2, page.number(),
                            "0-based page index = offset/limit = 6/3 = 2");
                    assertEquals(3, page.size(), "page size는 pageable.limit() 그대로");
                    assertEquals(4, page.totalPages(), "ceil(10/3) = 4");
                    assertTrue(page.hasNext(), "page 2 / totalPages 4 → 다음 페이지 존재");
                    assertTrue(page.hasPrevious());
                    assertFalse(page.isEmpty());
                })
                .verifyComplete();
    }

    @Test
    void findAllByPageableOnLastPageReportsNoNext() {
        // limit 3, offset 9 → page index 3, content는 마지막 1건만, hasNext=false
        Pageable pageable = Pageable.of(3, 9L);

        StepVerifier.create(support.operations().findAll(IdentityAccount.class, QuerySpec.empty(), pageable))
                .assertNext(page -> {
                    assertEquals(1, page.content().size(),
                            "마지막 페이지는 limit보다 작을 수 있다");
                    assertEquals(10L, page.totalElements());
                    assertEquals(3, page.number(), "offset 9 / limit 3 = 3");
                    assertEquals(4, page.totalPages());
                    assertFalse(page.hasNext(), "마지막 페이지 → hasNext=false");
                    assertTrue(page.hasPrevious());
                })
                .verifyComplete();
    }

    @Test
    void findSliceProbesOneExtraRowToReportHasNextWithoutCountQuery() {
        // limit 4, offset 0 → 4건 + probe로 5번째 행이 있는지 확인 → hasNext=true
        Pageable pageable = Pageable.of(4, 0L);

        StepVerifier.create(support.operations().findSlice(IdentityAccount.class, QuerySpec.empty(), pageable))
                .assertNext(slice -> {
                    assertEquals(4, slice.content().size(), "content는 정확히 limit만큼");
                    assertTrue(slice.hasNext(), "총 10건이므로 5번째 행이 존재 → hasNext=true");
                    assertEquals(0, slice.number());
                    assertFalse(slice.hasPrevious());
                })
                .verifyComplete();
    }

    @Test
    void findSliceOnLastPartialPageReportsNoNext() {
        // limit 5, offset 8 → 실제 2건만 존재, probe도 추가 행을 찾지 못함 → hasNext=false
        Pageable pageable = Pageable.of(5, 8L);

        StepVerifier.create(support.operations().findSlice(IdentityAccount.class, QuerySpec.empty(), pageable))
                .assertNext(slice -> {
                    assertEquals(2, slice.content().size(),
                            "마지막 페이지는 limit 보다 작은 content를 가질 수 있다");
                    assertFalse(slice.hasNext(), "추가 행이 없으므로 hasNext=false");
                    assertEquals(1, slice.number(), "offset 8 / limit 5 = 1");
                    assertTrue(slice.hasPrevious());
                })
                .verifyComplete();
    }
}
