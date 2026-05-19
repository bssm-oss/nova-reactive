package io.nova.r2dbc.integration;

import io.nova.sql.BindMarkerStrategy;
import io.nova.sql.Dialect;
import io.nova.sql.SchemaGenerator;
import io.nova.sql.SqlRenderer;
import io.nova.sql.SqlStatement;
import io.nova.r2dbc.R2dbcSqlExecutor;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * {@link R2dbcSqlExecutor}가 binding 값이 {@code null}일 때 사용하는
 * {@code statement.bindNull(i, Object.class)} 호출이 r2dbc-h2 driver와 호환되지 않는다는
 * 사실을 회귀 보호한다.
 *
 * <p><b>발견된 production 버그</b>: {@link R2dbcSqlExecutor#bind} 는 모든 null binding에 대해
 * {@code bindNull(index, Object.class)}를 호출하지만, r2dbc-h2 1.0.0은 이 형태를 거부하고
 * {@code IllegalArgumentException: Cannot encode null parameter of type java.lang.Object}을
 * 던진다. 결과적으로 H2 driver와 함께 nullable 컬럼이 있는 entity의 INSERT/UPDATE는 그 컬럼이
 * null로 채워지는 순간 실패한다 — 예: {@code @SoftDelete} entity의 초기 INSERT (deletedAt=null),
 * 사용자가 nullable 필드를 비워둔 모든 INSERT 등.
 *
 * <p>이 테스트는 production {@link R2dbcSqlExecutor}를 그대로 사용해 nullable Object binding이
 * 실패하는 사실을 단언한다 — driver/executor가 정상 동작으로 바뀌면 이 테스트가 깨지므로 회귀
 * 시 통합 테스트의 raw SQL seed 우회를 제거할 수 있다.
 */
class H2NullBindingIntegrationTest {
    @Test
    void nullObjectBindingIsRejectedByR2dbcH2Driver() {
        String dbName = "novanull_" + UUID.randomUUID().toString().replace("-", "");
        ConnectionFactory cf = ConnectionFactories.get(
                "r2dbc:h2:mem:///" + dbName + "?DB_CLOSE_DELAY=-1");
        Dialect noopDialect = new Dialect() {
            @Override public String name() { return "noop"; }
            @Override public String quote(String identifier) { return identifier; }
            @Override public BindMarkerStrategy bindMarkers() { return index -> "?"; }
            @Override public SqlRenderer sqlRenderer() { throw new UnsupportedOperationException(); }
            @Override public SchemaGenerator schemaGenerator() { throw new UnsupportedOperationException(); }
        };
        R2dbcSqlExecutor executor = new R2dbcSqlExecutor(cf, noopDialect);

        StepVerifier.create(executor.execute(new SqlStatement(
                        "create table nullable_rows (id bigint primary key, payload varchar(255))",
                        List.of())))
                .expectNextCount(1)
                .verifyComplete();

        // 두 번째 binding이 null. executor는 bindNull(1, Object.class)로 호출하므로 h2가 거부해야 한다.
        StepVerifier.create(executor.execute(new SqlStatement(
                        "insert into nullable_rows (id, payload) values (?, ?)",
                        Arrays.asList(1L, null))))
                .expectErrorMatches(error -> {
                    String message = error.getMessage();
                    return message != null
                            && message.toLowerCase().contains("cannot encode null parameter")
                            && message.contains("java.lang.Object");
                })
                .verify();
    }
}
