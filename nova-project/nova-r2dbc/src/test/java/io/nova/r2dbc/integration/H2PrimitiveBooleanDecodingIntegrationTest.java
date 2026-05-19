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

import java.util.List;
import java.util.UUID;

/**
 * Java primitive {@code boolean.class}를 {@code row.get(name, type)}으로 그대로 전달했을 때
 * r2dbc-h2 driver가 거부한다는 사실을 회귀 보호한다.
 *
 * <p><b>발견된 production 부조화</b>: nova의 {@code SimpleReactiveEntityOperations.mapRow}는
 * {@code PersistentProperty.javaType()}을 그대로 {@code row.get(name, type)}에 넘긴다. entity가
 * primitive {@code boolean active} 필드를 선언하면 {@code javaType()}이 {@code boolean.class}이고,
 * r2dbc-h2 1.0.0은 이 primitive class를 디코딩하지 못해
 * {@code IllegalArgumentException: Cannot decode value of type boolean}을 던진다. 결과적으로
 * H2 driver와 함께 primitive boolean 컬럼이 있는 entity를 round-trip 하면 SELECT가 실패한다.
 *
 * <p>이 테스트는 production {@link R2dbcSqlExecutor}와 보일러 plate {@code row.get(.., boolean.class)}
 * 경로를 그대로 사용해 driver가 primitive 클래스를 거부하는 사실을 단언한다 — driver가 primitive를
 * 수용하도록 바뀌면 이 테스트가 깨지고, 그 시점에 entity 모델에서 boxed type 강제를 제거할 수 있다.
 */
class H2PrimitiveBooleanDecodingIntegrationTest {
    @Test
    void primitiveBooleanClassDecodeIsRejectedByR2dbcH2Driver() {
        String dbName = "novabool_" + UUID.randomUUID().toString().replace("-", "");
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
                        "create table boolean_rows (id bigint primary key, active boolean not null)",
                        List.of())))
                .expectNextCount(1)
                .verifyComplete();
        StepVerifier.create(executor.execute(new SqlStatement(
                        "insert into boolean_rows (id, active) values (?, ?)",
                        List.of(1L, true))))
                .expectNextCount(1)
                .verifyComplete();

        // primitive class를 그대로 row.get에 넘기면 driver가 거부한다.
        StepVerifier.create(executor.queryOne(
                        new SqlStatement("select active from boolean_rows where id = ?", List.of(1L)),
                        row -> row.get("active", boolean.class)))
                .expectErrorMatches(error -> {
                    String message = error.getMessage();
                    return error instanceof IllegalArgumentException
                            && message != null
                            && message.toLowerCase().contains("cannot decode value of type boolean");
                })
                .verify();

        // boxed Boolean.class는 정상 동작한다는 대조도 같이 검증한다.
        StepVerifier.create(executor.queryOne(
                        new SqlStatement("select active from boolean_rows where id = ?", List.of(1L)),
                        row -> row.get("active", Boolean.class)))
                .expectNext(Boolean.TRUE)
                .verifyComplete();
    }
}
