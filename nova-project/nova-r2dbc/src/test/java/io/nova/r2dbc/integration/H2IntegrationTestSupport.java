package io.nova.r2dbc.integration;

import io.nova.core.EntityStateDetector;
import io.nova.core.SimpleReactiveEntityOperations;
import io.nova.dialect.h2.H2Dialect;
import io.nova.json.JsonCodec;
import io.nova.metadata.DefaultNamingStrategy;
import io.nova.metadata.EntityMetadataFactory;
import io.nova.r2dbc.R2dbcSqlExecutor;
import io.nova.r2dbc.R2dbcTransactionManager;
import io.nova.sql.Dialect;
import io.nova.sql.SqlStatement;
import io.nova.tx.SimpleReactiveTransactionOperations;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.UUID;

/**
 * H2 in-memory R2DBC integration test의 부트스트랩 helper다.
 *
 * <p>각 인스턴스는 자체 in-memory DB(고유 이름)를 가지며, production {@link H2Dialect},
 * {@link R2dbcSqlExecutor}, {@link R2dbcTransactionManager}, {@link SimpleReactiveEntityOperations}를
 * 한꺼번에 조립한다. 테스트는 fixture entity에 맞춰 {@link #execute(String)}로 직접 DDL을 실행하거나,
 * dialect의 {@link io.nova.sql.SchemaGenerator}로 생성한 SQL을 그대로 실행할 수 있다.
 *
 * <p>cycle 9에서 production {@link H2Dialect}와 r2dbc-h2 driver 사이의 3개 호환성 버그가 fix된 이후
 * (RETURNING 절 제거, null binding fallback, primitive row.get wrapping) wrapper dialect 없이 production
 * {@link H2Dialect}를 그대로 사용한다.
 */
final class H2IntegrationTestSupport {
    private final ConnectionFactory connectionFactory;
    private final Dialect dialect;
    private final EntityMetadataFactory metadataFactory;
    private final R2dbcSqlExecutor sqlExecutor;
    private final R2dbcTransactionManager transactionManager;
    private final SimpleReactiveEntityOperations operations;

    private H2IntegrationTestSupport(
            ConnectionFactory connectionFactory,
            Dialect dialect,
            EntityMetadataFactory metadataFactory,
            R2dbcSqlExecutor sqlExecutor,
            R2dbcTransactionManager transactionManager,
            SimpleReactiveEntityOperations operations
    ) {
        this.connectionFactory = connectionFactory;
        this.dialect = dialect;
        this.metadataFactory = metadataFactory;
        this.sqlExecutor = sqlExecutor;
        this.transactionManager = transactionManager;
        this.operations = operations;
    }

    /**
     * 호출마다 고유한 in-memory DB 이름을 사용하는 새 support 인스턴스를 만든다.
     * {@code DB_CLOSE_DELAY=-1}로 JVM 수명 동안 DB가 사라지지 않게 한다. 같은 support 인스턴스의
     * connectionFactory는 동일 DB를 가리키므로 여러 connection 사이에 데이터가 공유된다.
     */
    static H2IntegrationTestSupport create() {
        return create("");
    }

    /**
     * {@code @Json} 필드를 사용하는 통합 테스트용으로 {@link JsonCodec}을 metadata factory에 주입한 support를
     * 만든다. URL parameter는 기본값을 쓴다.
     */
    static H2IntegrationTestSupport create(JsonCodec jsonCodec) {
        String dbName = "novaint_" + UUID.randomUUID().toString().replace("-", "");
        String url = "r2dbc:h2:mem:///" + dbName + "?DB_CLOSE_DELAY=-1";
        ConnectionFactory connectionFactory = ConnectionFactories.get(url);
        Dialect dialect = new H2Dialect();
        EntityMetadataFactory metadataFactory =
                new EntityMetadataFactory(new DefaultNamingStrategy(), jsonCodec);
        R2dbcSqlExecutor sqlExecutor = new R2dbcSqlExecutor(connectionFactory, dialect);
        R2dbcTransactionManager transactionManager = new R2dbcTransactionManager(connectionFactory);
        SimpleReactiveEntityOperations operations = new SimpleReactiveEntityOperations(
                metadataFactory,
                dialect,
                sqlExecutor,
                new EntityStateDetector(),
                new SimpleReactiveTransactionOperations(transactionManager)
        );
        return new H2IntegrationTestSupport(
                connectionFactory, dialect, metadataFactory, sqlExecutor, transactionManager, operations);
    }

    /**
     * 추가 URL parameter(예: {@code "LOCK_TIMEOUT=50"})를 덧붙여 support를 만든다. parameter는
     * 앞에 {@code ;}를 포함하지 않은 채로 전달한다 — 비어 있으면 기본 URL을 그대로 사용한다.
     */
    static H2IntegrationTestSupport create(String extraUrlParameters) {
        String dbName = "novaint_" + UUID.randomUUID().toString().replace("-", "");
        StringBuilder url = new StringBuilder("r2dbc:h2:mem:///").append(dbName).append("?DB_CLOSE_DELAY=-1");
        if (extraUrlParameters != null && !extraUrlParameters.isBlank()) {
            url.append(';').append(extraUrlParameters);
        }
        ConnectionFactory connectionFactory = ConnectionFactories.get(url.toString());
        Dialect dialect = new H2Dialect();
        EntityMetadataFactory metadataFactory = new EntityMetadataFactory(new DefaultNamingStrategy());
        R2dbcSqlExecutor sqlExecutor = new R2dbcSqlExecutor(connectionFactory, dialect);
        R2dbcTransactionManager transactionManager = new R2dbcTransactionManager(connectionFactory);
        SimpleReactiveEntityOperations operations = new SimpleReactiveEntityOperations(
                metadataFactory,
                dialect,
                sqlExecutor,
                new EntityStateDetector(),
                new SimpleReactiveTransactionOperations(transactionManager)
        );
        return new H2IntegrationTestSupport(
                connectionFactory, dialect, metadataFactory, sqlExecutor, transactionManager, operations);
    }

    ConnectionFactory connectionFactory() {
        return connectionFactory;
    }

    EntityMetadataFactory metadataFactory() {
        return metadataFactory;
    }

    R2dbcSqlExecutor sqlExecutor() {
        return sqlExecutor;
    }

    R2dbcTransactionManager transactionManager() {
        return transactionManager;
    }

    SimpleReactiveEntityOperations operations() {
        return operations;
    }

    Dialect dialect() {
        return dialect;
    }

    /**
     * 단일 DDL/DML 문장을 즉시 실행하고 완료될 때까지 블록 없이 verify한다. 테스트 setup에서
     * 스키마를 만들 때 사용한다.
     */
    void execute(String sql) {
        StepVerifier.create(sqlExecutor.execute(new SqlStatement(sql, List.of())))
                .expectNextCount(1)
                .verifyComplete();
    }
}
