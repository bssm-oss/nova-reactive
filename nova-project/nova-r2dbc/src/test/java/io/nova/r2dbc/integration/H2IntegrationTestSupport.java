package io.nova.r2dbc.integration;

import io.nova.core.EntityStateDetector;
import io.nova.core.SimpleReactiveEntityOperations;
import io.nova.dialect.h2.H2Dialect;
import io.nova.metadata.DefaultNamingStrategy;
import io.nova.metadata.EntityMetadataFactory;
import io.nova.query.LockMode;
import io.nova.r2dbc.R2dbcSqlExecutor;
import io.nova.r2dbc.R2dbcTransactionManager;
import io.nova.sql.BindMarkerStrategy;
import io.nova.sql.Dialect;
import io.nova.sql.SchemaGenerator;
import io.nova.sql.SqlRenderer;
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
 * <p>각 인스턴스는 자체 in-memory DB(고유 이름)를 가지며, {@link H2Dialect}, {@link R2dbcSqlExecutor},
 * {@link R2dbcTransactionManager}, {@link SimpleReactiveEntityOperations}를 한꺼번에 조립한다.
 * 테스트는 fixture entity에 맞춰 {@link #execute(String)}로 직접 DDL을 실행하거나, dialect의
 * {@link io.nova.sql.SchemaGenerator}로 생성한 SQL을 그대로 실행할 수 있다.
 *
 * <h2>RETURNING 절 workaround</h2>
 * 통합 테스트는 H2 driver(r2dbc-h2 1.0.0 → h2 2.1.214)와 함께 동작해야 한다. 그러나 production
 * {@link H2Dialect}는 {@code usesReturningForGeneratedKeys()=true}로 IDENTITY INSERT 끝에
 * {@code returning "id"} 절을 붙이고, h2 2.1.214는 이 구문을 거부한다 — 따라서 이 helper는
 * dialect를 {@link H2IntegrationDialect}로 감싸 {@code usesReturningForGeneratedKeys()=false}로
 * 강제하고 R2DBC의 {@code Statement.returnGeneratedValues(...)} 경로로 키를 회수하게 한다.
 * H2 dialect와 driver 사이의 이 불일치는 별도 {@code H2DialectReturningClauseIntegrationTest}에서
 * 회귀 보호로 명시 검증한다.
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
        Dialect dialect = new H2IntegrationDialect(new H2Dialect());
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

    /**
     * production {@link H2Dialect}를 감싸 {@code usesReturningForGeneratedKeys()=false}로 강제하고,
     * SqlRenderer가 만드는 INSERT SQL 뒤의 {@code returning "id"} 절도 제거한다 — h2 2.1.214가
     * 이 구문을 거부하기 때문이다. 그 외 모든 동작은 {@link H2Dialect}에 위임한다.
     */
    private static final class H2IntegrationDialect implements Dialect {
        private final H2Dialect delegate;
        private final SqlRenderer renderer;

        H2IntegrationDialect(H2Dialect delegate) {
            this.delegate = delegate;
            this.renderer = new StripReturningSqlRenderer(delegate.sqlRenderer());
        }

        @Override
        public String name() {
            return delegate.name();
        }

        @Override
        public String quote(String identifier) {
            return delegate.quote(identifier);
        }

        @Override
        public BindMarkerStrategy bindMarkers() {
            return delegate.bindMarkers();
        }

        @Override
        public SqlRenderer sqlRenderer() {
            return renderer;
        }

        @Override
        public SchemaGenerator schemaGenerator() {
            return delegate.schemaGenerator();
        }

        @Override
        public boolean usesReturningForGeneratedKeys() {
            // H2 dialect production 기본값은 true지만 h2 2.1.214가 RETURNING을 거부하므로
            // 통합 테스트는 R2DBC Statement.returnGeneratedValues() 경로를 강제해야 한다.
            return false;
        }

        @Override
        public String lockClause(LockMode mode) {
            return delegate.lockClause(mode);
        }

        @Override
        public String sequenceNextValueSql(String sequenceName) {
            return delegate.sequenceNextValueSql(sequenceName);
        }
    }

    /**
     * {@link H2Dialect#sqlRenderer()} 결과를 감싸 {@code insert(...)}가 만든 SqlStatement에서
     * 트레일링 {@code returning "..."} 절만 제거한다. 나머지 메서드는 그대로 위임한다.
     */
    private static final class StripReturningSqlRenderer implements SqlRenderer {
        private final SqlRenderer delegate;

        StripReturningSqlRenderer(SqlRenderer delegate) {
            this.delegate = delegate;
        }

        @Override
        public SqlStatement insert(io.nova.metadata.EntityMetadata<?> metadata, Object entity) {
            SqlStatement original = delegate.insert(metadata, entity);
            String sql = original.sql();
            int idx = sql.lastIndexOf(" returning ");
            if (idx >= 0) {
                sql = sql.substring(0, idx);
            }
            return new SqlStatement(sql, original.bindings());
        }

        @Override public SqlStatement update(io.nova.metadata.EntityMetadata<?> metadata, Object entity) {
            return delegate.update(metadata, entity);
        }

        @Override public SqlStatement update(io.nova.metadata.EntityMetadata<?> metadata, Object entity, Iterable<String> fields) {
            return delegate.update(metadata, entity, fields);
        }

        @Override public SqlStatement deleteById(io.nova.metadata.EntityMetadata<?> metadata, Object id) {
            return delegate.deleteById(metadata, id);
        }

        @Override public SqlStatement selectById(io.nova.metadata.EntityMetadata<?> metadata, Object id) {
            return delegate.selectById(metadata, id);
        }

        @Override public SqlStatement select(io.nova.metadata.EntityMetadata<?> metadata, io.nova.query.QuerySpec querySpec) {
            return delegate.select(metadata, querySpec);
        }

        @Override public SqlStatement selectProjection(io.nova.metadata.EntityMetadata<?> metadata, List<String> fields, io.nova.query.QuerySpec querySpec) {
            return delegate.selectProjection(metadata, fields, querySpec);
        }

        @Override public SqlStatement count(io.nova.metadata.EntityMetadata<?> metadata, io.nova.query.QuerySpec querySpec) {
            return delegate.count(metadata, querySpec);
        }

        @Override public SqlStatement exists(io.nova.metadata.EntityMetadata<?> metadata, io.nova.query.QuerySpec querySpec) {
            return delegate.exists(metadata, querySpec);
        }

        @Override public SqlStatement deleteByIds(io.nova.metadata.EntityMetadata<?> metadata, List<Object> ids) {
            return delegate.deleteByIds(metadata, ids);
        }

        @Override public SqlStatement deleteByQuery(io.nova.metadata.EntityMetadata<?> metadata, io.nova.query.QuerySpec querySpec) {
            return delegate.deleteByQuery(metadata, querySpec);
        }

        @Override public SqlStatement updateByQuery(io.nova.metadata.EntityMetadata<?> metadata, java.util.LinkedHashMap<String, Object> fieldValues, io.nova.query.QuerySpec querySpec) {
            return delegate.updateByQuery(metadata, fieldValues, querySpec);
        }

        @Override public SqlStatement softDeleteById(io.nova.metadata.EntityMetadata<?> metadata, Object id, Object deletedAt) {
            return delegate.softDeleteById(metadata, id, deletedAt);
        }

        @Override public SqlStatement softDeleteByIds(io.nova.metadata.EntityMetadata<?> metadata, List<Object> ids, Object deletedAt) {
            return delegate.softDeleteByIds(metadata, ids, deletedAt);
        }

        @Override public SqlStatement softDeleteByQuery(io.nova.metadata.EntityMetadata<?> metadata, io.nova.query.QuerySpec querySpec, Object deletedAt) {
            return delegate.softDeleteByQuery(metadata, querySpec, deletedAt);
        }

        @Override public SqlStatement deleteByEntity(io.nova.metadata.EntityMetadata<?> metadata, Object entity) {
            return delegate.deleteByEntity(metadata, entity);
        }

        @Override public SqlStatement softDeleteByEntity(io.nova.metadata.EntityMetadata<?> metadata, Object entity, Object deletedAt) {
            return delegate.softDeleteByEntity(metadata, entity, deletedAt);
        }

        @Override public SqlStatement aggregate(io.nova.metadata.EntityMetadata<?> metadata, io.nova.query.AggregateSpec spec) {
            return delegate.aggregate(metadata, spec);
        }
    }
}
