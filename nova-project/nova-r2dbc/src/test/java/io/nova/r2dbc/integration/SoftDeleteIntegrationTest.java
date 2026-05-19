package io.nova.r2dbc.integration;

import io.nova.r2dbc.integration.IntegrationFixtures.SoftDeleteAccount;
import io.nova.sql.SqlStatement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * {@code @SoftDelete} entity가 실제 H2에서 논리 삭제로 동작하는지 검증한다 — 삭제 후
 * {@code findById}는 빈 결과를 반환하지만, 실제 row는 테이블에 남아 있고 {@code deleted_at}만 채워진다.
 *
 * <h2>Seed row를 raw SQL로 넣는 이유</h2>
 * {@link io.nova.r2dbc.R2dbcSqlExecutor}의 {@code bind} 헬퍼는 binding 값이 {@code null}일 때
 * {@code statement.bindNull(i, Object.class)}를 호출한다. r2dbc-h2 1.0.0 driver는 이 형태를 거부하고
 * {@code Cannot encode null parameter of type java.lang.Object} 예외를 던진다 — {@code SoftDeleteAccount}의
 * 초기 {@code deletedAt}이 null이라 INSERT 시점에 binding이 null로 들어가기 때문이다. 이 통합 테스트는
 * soft-delete UPDATE 경로 자체의 server-side 동작을 검증하는 것이 목적이므로, 초기 row는 raw SQL로
 * NULL 리터럴을 사용해 삽입하고 nullable Object binding 버그는 별도 회귀 테스트가 보호한다
 * ({@link H2NullBindingIntegrationTest} 참고).
 */
class SoftDeleteIntegrationTest {
    private H2IntegrationTestSupport support;

    @BeforeEach
    void setUp() {
        support = H2IntegrationTestSupport.create();
        // AbstractSchemaGenerator는 Instant 타입을 sqlType()에 매핑하지 않으므로 DDL을 직접 작성한다.
        // H2 R2DBC driver는 Instant를 TIMESTAMP WITH TIME ZONE 컬럼에 매핑한다.
        support.execute(
                "create table \"soft_delete_accounts\" ("
                        + "\"id\" bigint primary key, "
                        + "\"email_address\" varchar(255), "
                        + "\"deleted_at\" timestamp with time zone)");
    }

    /**
     * raw SQL로 NULL deleted_at을 가진 살아있는 row를 삽입한다. INSERT 시 nullable Object binding
     * 우회를 위해 SQL 리터럴 {@code NULL}을 직접 사용한다.
     */
    private void seedAliveRow(long id, String email) {
        support.execute("insert into \"soft_delete_accounts\" (\"id\", \"email_address\", \"deleted_at\") "
                + "values (" + id + ", '" + email + "', NULL)");
    }

    @Test
    void deletesAccountLogicallyAndExcludesFromFindById() {
        seedAliveRow(1L, "soft@nova.io");
        SoftDeleteAccount account = new SoftDeleteAccount(1L, "soft@nova.io");

        // alive 상태 확인.
        StepVerifier.create(support.operations().findById(SoftDeleteAccount.class, 1L))
                .expectNextCount(1)
                .verifyComplete();

        StepVerifier.create(support.operations().delete(account))
                .expectNext(1L)
                .verifyComplete();

        // findById는 soft-delete alive 가드 때문에 deleted row를 발견하지 못해 empty여야 한다.
        StepVerifier.create(support.operations().findById(SoftDeleteAccount.class, 1L))
                .verifyComplete();
    }

    @Test
    void retainsRowWithDeletedAtPopulatedAfterSoftDelete() {
        seedAliveRow(2L, "ghost@nova.io");
        SoftDeleteAccount account = new SoftDeleteAccount(2L, "ghost@nova.io");

        Instant before = Instant.now();
        StepVerifier.create(support.operations().delete(account))
                .expectNext(1L)
                .verifyComplete();
        Instant after = Instant.now();

        // SqlExecutor를 통한 raw SELECT는 alive 가드를 거치지 않으므로 deleted row를 그대로 본다.
        SqlStatement raw = new SqlStatement(
                "select \"id\", \"email_address\", \"deleted_at\" from \"soft_delete_accounts\" where \"id\" = ?",
                List.of(2L));
        StepVerifier.create(support.sqlExecutor().queryOne(raw, row -> new Object[]{
                        row.get("id", Long.class),
                        row.get("email_address", String.class),
                        row.get("deleted_at", Instant.class)
                }))
                .assertNext(values -> {
                    assertEquals(2L, values[0]);
                    assertEquals("ghost@nova.io", values[1]);
                    Instant deletedAt = (Instant) values[2];
                    assertNotNull(deletedAt, "soft delete 이후 deleted_at은 NULL이 아니어야 한다");
                    // delete 동작 전후 시각 사이에 있어야 한다.
                    assertFalse(deletedAt.isBefore(before),
                            "deleted_at은 delete 호출 전 시각보다 이전일 수 없다: " + deletedAt + " >= " + before);
                    assertFalse(deletedAt.isAfter(after),
                            "deleted_at은 delete 호출 후 시각보다 이후일 수 없다: " + deletedAt + " <= " + after);
                })
                .verifyComplete();
    }

    @Test
    void countExcludesSoftDeletedRows() {
        seedAliveRow(10L, "alive@nova.io");
        seedAliveRow(11L, "byebye@nova.io");

        SoftDeleteAccount toDelete = new SoftDeleteAccount(11L, "byebye@nova.io");
        StepVerifier.create(support.operations().delete(toDelete))
                .expectNext(1L)
                .verifyComplete();

        StepVerifier.create(support.operations().count(SoftDeleteAccount.class, null))
                .expectNext(1L)
                .verifyComplete();
    }
}
