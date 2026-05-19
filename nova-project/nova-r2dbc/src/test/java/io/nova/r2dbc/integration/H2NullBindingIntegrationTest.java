package io.nova.r2dbc.integration;

import io.nova.dialect.h2.H2Dialect;
import io.nova.r2dbc.R2dbcSqlExecutor;
import io.nova.sql.Dialect;
import io.nova.sql.SqlStatement;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * production {@link H2Dialect}의 {@code nullBindClass()=String.class} override 덕분에
 * nullable 컬럼이 있는 SQL이 r2dbc-h2 driver에서 round-trip 되는 것을 회귀 보호한다.
 *
 * <p>cycle 9 R1 이전에는 {@link R2dbcSqlExecutor#bind}가 모든 null binding을
 * {@code bindNull(index, Object.class)}로 보내 r2dbc-h2가
 * {@code IllegalArgumentException: Cannot encode null parameter of type java.lang.Object}로
 * 거부했다. 현재는 dialect-provided fallback 타입을 사용해 driver가 받아들이는 타입(H2의 경우
 * {@code String.class})으로 전달한다. dialect/executor가 다시 {@code Object.class}를 강제로
 * 사용하도록 바뀌면 이 테스트가 깨진다.
 */
class H2NullBindingIntegrationTest {
    @Test
    void nullBindingIsAcceptedByR2dbcH2WhenDialectProvidesFallbackType() {
        String dbName = "novanull_" + UUID.randomUUID().toString().replace("-", "");
        ConnectionFactory cf = ConnectionFactories.get(
                "r2dbc:h2:mem:///" + dbName + "?DB_CLOSE_DELAY=-1");
        Dialect dialect = new H2Dialect();
        assertEquals(String.class, dialect.nullBindClass(),
                "H2Dialect는 r2dbc-h2 호환을 위해 String.class를 null binding 타입으로 사용해야 한다");

        R2dbcSqlExecutor executor = new R2dbcSqlExecutor(cf, dialect);

        StepVerifier.create(executor.execute(new SqlStatement(
                        "create table nullable_rows (id bigint primary key, payload varchar(255))",
                        List.of())))
                .expectNextCount(1)
                .verifyComplete();

        // 두 번째 binding이 null. executor는 dialect.nullBindClass() == String.class로 전달하므로
        // r2dbc-h2가 받아들여 한 행이 정상 insert되어야 한다.
        StepVerifier.create(executor.execute(new SqlStatement(
                        "insert into nullable_rows (id, payload) values (?, ?)",
                        Arrays.asList(1L, null))))
                .expectNext(1L)
                .verifyComplete();

        // INSERT가 실제 row로 들어갔는지 COUNT로 확인한다.
        StepVerifier.create(executor.queryOne(
                        new SqlStatement("select count(*) as c from nullable_rows where id = ?", List.of(1L)),
                        row -> row.get("c", Long.class)))
                .expectNext(1L)
                .verifyComplete();
    }
}
