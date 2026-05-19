package io.nova.r2dbc.integration;

import io.nova.core.EntityStateDetector;
import io.nova.core.SimpleReactiveEntityOperations;
import io.nova.dialect.h2.H2Dialect;
import io.nova.metadata.DefaultNamingStrategy;
import io.nova.metadata.EntityMetadataFactory;
import io.nova.r2dbc.R2dbcSqlExecutor;
import io.nova.r2dbc.R2dbcTransactionManager;
import io.nova.r2dbc.integration.IntegrationFixtures.IdentityAccount;
import io.nova.sql.Dialect;
import io.nova.sql.SqlStatement;
import io.nova.tx.SimpleReactiveTransactionOperations;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.R2dbcException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.UUID;

/**
 * H2 dialect의 {@code usesReturningForGeneratedKeys()=true}와 함께 묶인 r2dbc-h2 driver
 * (H2 2.1.214) 사이의 호환성 부재를 회귀 보호한다.
 *
 * <p><b>발견된 production 버그</b>: {@link H2Dialect#usesReturningForGeneratedKeys()}는
 * {@code true}를 반환해 IDENTITY INSERT 끝에 {@code returning "id"} 절을 붙이지만, H2 2.1.214는
 * {@code INSERT ... VALUES (...) RETURNING ...} 형태를 거부하고 {@code R2dbcBadGrammarException}
 * ({@code [42000-214]})를 던진다. 통합 테스트에서 IDENTITY 라운드트립 시나리오는 이 동작을
 * 우회한 별도 dialect를 사용한다 ({@link H2IntegrationTestSupport} 참고).
 *
 * <p>이 테스트는 production {@link H2Dialect}를 그대로 사용해 driver가 RETURNING 절을 거부하는
 * 사실 자체를 단언한다 — h2 driver/h2 엔진이 업그레이드되어 RETURNING이 지원되면 이 테스트가
 * 실패하므로, 그 시점에 dialect 우회를 제거하고 통합 테스트가 production H2Dialect를 직접 쓰도록
 * 정리해야 한다.
 */
class H2DialectReturningClauseIntegrationTest {
    @Test
    void productionH2DialectInsertWithReturningIsRejectedByH2_2_1_214() {
        // 직접 production H2Dialect를 사용해 INSERT + RETURNING SQL을 통째로 driver로 보낸다.
        String dbName = "novabug_" + UUID.randomUUID().toString().replace("-", "");
        ConnectionFactory cf = ConnectionFactories.get(
                "r2dbc:h2:mem:///" + dbName + "?DB_CLOSE_DELAY=-1");
        Dialect dialect = new H2Dialect();
        EntityMetadataFactory metadataFactory = new EntityMetadataFactory(new DefaultNamingStrategy());
        R2dbcSqlExecutor executor = new R2dbcSqlExecutor(cf, dialect);
        R2dbcTransactionManager txManager = new R2dbcTransactionManager(cf);
        SimpleReactiveEntityOperations operations = new SimpleReactiveEntityOperations(
                metadataFactory,
                dialect,
                executor,
                new EntityStateDetector(),
                new SimpleReactiveTransactionOperations(txManager));

        // 스키마 생성은 production schema generator가 만드는 DDL로 한다 — DDL 자체는 h2가 받아들인다.
        StepVerifier.create(executor.execute(new SqlStatement(
                        operations.createTableSql(IdentityAccount.class), List.of())))
                .expectNextCount(1)
                .verifyComplete();

        IdentityAccount account = new IdentityAccount("bug@nova.io", true);
        // save()는 dialect.sqlRenderer().insert(...)로 RETURNING 절이 붙은 SQL을 만들고,
        // dialect.usesReturningForGeneratedKeys()==true 이므로 driver에 그대로 보낸다.
        // H2 2.1.214는 이를 거부하므로 R2dbcException이 발생해야 한다.
        StepVerifier.create(operations.save(account))
                .expectErrorMatches(error -> {
                    if (!(error instanceof R2dbcException ex)) {
                        return false;
                    }
                    String message = ex.getMessage();
                    return message != null
                            && message.toLowerCase().contains("syntax error")
                            && message.toLowerCase().contains("returning");
                })
                .verify();
    }
}
