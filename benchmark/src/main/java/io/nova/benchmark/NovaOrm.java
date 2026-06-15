package io.nova.benchmark;

import io.nova.benchmark.entity.BenchUser;
import io.nova.core.EntityStateDetector;
import io.nova.core.ReactiveEntityOperations;
import io.nova.core.SimpleReactiveEntityOperations;
import io.nova.dialect.h2.H2Dialect;
import io.nova.dialect.postgresql.PostgresqlDialect;
import io.nova.metadata.DefaultNamingStrategy;
import io.nova.metadata.EntityMetadataFactory;
import io.nova.query.QuerySpec;
import io.nova.r2dbc.R2dbcSqlExecutor;
import io.nova.r2dbc.R2dbcTransactionManager;
import io.nova.schema.SimpleSchemaInitializer;
import io.nova.sql.Dialect;
import io.r2dbc.pool.ConnectionPool;
import io.r2dbc.pool.ConnectionPoolConfiguration;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryOptions;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.ArrayList;
import java.util.List;

/**
 * Nova(R2DBC + Project Reactor) 구현. backend(H2 / PostgreSQL)를 받아 동일 시나리오를 구동한다. 커넥션은
 * r2dbc-pool(max 20)로 재사용하고, 각 연산은 {@code block()}으로 완료를 대기한다.
 */
final class NovaOrm implements OrmBenchmark {

    private final String name;
    private final ConnectionPool connectionFactory;
    private final ReactiveEntityOperations operations;
    private final SimpleSchemaInitializer schema;

    private NovaOrm(String name, ConnectionFactory base, Dialect dialect, int poolSize) {
        this.name = name;
        this.connectionFactory = new ConnectionPool(
                ConnectionPoolConfiguration.builder(base).initialSize(Math.min(5, poolSize)).maxSize(poolSize).build());
        EntityMetadataFactory metadataFactory = new EntityMetadataFactory(new DefaultNamingStrategy());
        R2dbcSqlExecutor executor = new R2dbcSqlExecutor(connectionFactory, dialect);
        R2dbcTransactionManager txManager = new R2dbcTransactionManager(connectionFactory);
        this.operations = new SimpleReactiveEntityOperations(
                metadataFactory, dialect, executor, new EntityStateDetector(), txManager);
        this.schema = new SimpleSchemaInitializer(operations, metadataFactory, dialect);
    }

    static NovaOrm h2(int poolSize) {
        ConnectionFactory base = ConnectionFactories.get("r2dbc:h2:mem:///novabench?DB_CLOSE_DELAY=-1");
        return new NovaOrm("Nova (R2DBC, reactive)", base, new H2Dialect(), poolSize);
    }

    static NovaOrm postgres(String host, int port, String db, String user, String password, int poolSize) {
        ConnectionFactory base = ConnectionFactories.get(ConnectionFactoryOptions.builder()
                .option(ConnectionFactoryOptions.DRIVER, "postgresql")
                .option(ConnectionFactoryOptions.HOST, host)
                .option(ConnectionFactoryOptions.PORT, port)
                .option(ConnectionFactoryOptions.DATABASE, db)
                .option(ConnectionFactoryOptions.USER, user)
                .option(ConnectionFactoryOptions.PASSWORD, password)
                .build());
        return new NovaOrm("Nova (R2DBC, reactive)", base, new PostgresqlDialect(), poolSize);
    }

    static NovaOrm postgres(PostgreSQLContainer<?> container, int poolSize) {
        return postgres(container.getHost(), container.getFirstMappedPort(),
                container.getDatabaseName(), container.getUsername(), container.getPassword(), poolSize);
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public void setupSchema() {
        // 컨테이너/메모리 DB가 깨끗하지 않을 수 있으므로 recreate로 멱등하게 만든다.
        schema.recreate(BenchUser.class).block();
    }

    @Override
    public void clear() {
        schema.recreate(BenchUser.class).block();
    }

    @Override
    public List<Long> insert(int n) {
        List<Long> ids = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            BenchUser saved = operations.save(new BenchUser("user" + i, "user" + i + "@nova.io", 20 + (i % 50))).block();
            ids.add(saved.getId());
        }
        return ids;
    }

    @Override
    public int findByIds(List<Long> ids) {
        int found = 0;
        for (Long id : ids) {
            if (operations.findById(BenchUser.class, id).block() != null) {
                found++;
            }
        }
        return found;
    }

    @Override
    public int findAll() {
        List<BenchUser> all = operations.findAll(BenchUser.class, QuerySpec.empty()).collectList().block();
        return all == null ? 0 : all.size();
    }

    @Override
    public void updateByIds(List<Long> ids) {
        for (Long id : ids) {
            BenchUser user = operations.findById(BenchUser.class, id).block();
            user.setAge(user.getAge() + 1);
            operations.save(user).block();
        }
    }

    @Override
    public void deleteByIds(List<Long> ids) {
        for (Long id : ids) {
            operations.deleteById(BenchUser.class, id).block();
        }
    }

    @Override
    public double concurrentFindOpsPerSec(List<Long> ids, int concurrency, int totalOps) {
        long start = System.nanoTime();
        // concurrency개를 in-flight로 유지하며 totalOps번 findById. 블로킹 스레드 없이 r2dbc-pool에서 동시 처리.
        reactor.core.publisher.Flux.range(0, totalOps)
                .flatMap(i -> operations.findById(BenchUser.class, ids.get(i % ids.size())), concurrency)
                .then()
                .block();
        long elapsed = System.nanoTime() - start;
        return totalOps / (elapsed / 1_000_000_000.0);
    }

    @Override
    public void close() {
        connectionFactory.dispose();
    }
}
