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
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * H2 dialect가 RETURNING 절을 사용하지 않고 R2DBC SPI의 {@code Statement.returnGeneratedValues(...)}
 * 경로로 IDENTITY 키를 회수하는 end-to-end 동작을 회귀 보호한다.
 *
 * <p>cycle 9 R1 이전에는 H2Dialect가 {@code usesReturningForGeneratedKeys()=true}로
 * INSERT 끝에 {@code returning "id"}를 붙였지만 H2 2.1.214는 이 구문을 거부했다. 현재는
 * dialect override가 제거되어 기본값({@code false})으로 회수 경로가 통일된다. 이 테스트는
 * production {@link H2Dialect}를 직접 사용해 IDENTITY INSERT가 성공하고 회수된 키가 entity에
 * 주입되는지 확인한다 — driver/dialect가 다시 RETURNING 경로로 돌아가면 이 테스트가 깨진다.
 */
class H2DialectReturningClauseIntegrationTest {
    @Test
    void productionH2DialectInsertWithoutReturningSucceedsViaR2dbcGeneratedValues() {
        String dbName = "novabug_" + UUID.randomUUID().toString().replace("-", "");
        ConnectionFactory cf = ConnectionFactories.get(
                "r2dbc:h2:mem:///" + dbName + "?DB_CLOSE_DELAY=-1");
        Dialect dialect = new H2Dialect();
        // H2Dialect는 RETURNING을 사용하지 않으므로 R2DBC SPI 경로로 키를 회수해야 한다.
        assertFalse(dialect.usesReturningForGeneratedKeys(),
                "H2Dialect는 RETURNING을 끄고 R2DBC Statement.returnGeneratedValues 경로를 사용해야 한다");

        EntityMetadataFactory metadataFactory = new EntityMetadataFactory(new DefaultNamingStrategy());
        R2dbcSqlExecutor executor = new R2dbcSqlExecutor(cf, dialect);
        R2dbcTransactionManager txManager = new R2dbcTransactionManager(cf);
        SimpleReactiveEntityOperations operations = new SimpleReactiveEntityOperations(
                metadataFactory,
                dialect,
                executor,
                new EntityStateDetector(),
                new SimpleReactiveTransactionOperations(txManager));

        StepVerifier.create(executor.execute(new SqlStatement(
                        operations.createTableSql(IdentityAccount.class), List.of())))
                .expectNextCount(1)
                .verifyComplete();

        // SqlRenderer가 만드는 INSERT SQL에는 RETURNING 절이 붙지 않아야 한다.
        SqlStatement insertSql = dialect.sqlRenderer().insert(
                metadataFactory.getEntityMetadata(IdentityAccount.class),
                new IdentityAccount("probe@nova.io", true));
        assertFalse(insertSql.sql().toLowerCase().contains("returning"),
                "H2Dialect의 INSERT SQL은 'returning' 절을 포함하지 않아야 한다: " + insertSql.sql());

        IdentityAccount account = new IdentityAccount("bug@nova.io", true);
        StepVerifier.create(operations.save(account))
                .assertNext(saved -> {
                    assertNotNull(saved.getId(),
                            "R2DBC Statement.returnGeneratedValues(...) 경로로 IDENTITY 키가 주입되어야 한다");
                    assertTrue(saved.getId() > 0L);
                    assertEquals("bug@nova.io", saved.getEmail());
                    assertTrue(saved.isActive());
                })
                .verifyComplete();
    }
}
