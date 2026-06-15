package io.nova.benchmark;

import io.nova.Nova;
import io.nova.benchmark.entity.BenchUser;
import io.nova.core.EntityStateDetector;
import io.nova.core.ReactiveEntityOperations;
import io.nova.core.SimpleReactiveEntityOperations;
import io.nova.dialect.h2.H2Dialect;
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

import java.util.ArrayList;
import java.util.List;

/**
 * Nova(R2DBC + Project Reactor) 구현. {@link Nova} 부트스트랩 대신 스택을 직접 조립해 Nova 자체의
 * {@link SimpleSchemaInitializer}로 H2 DDL을 생성한다. 각 연산은 {@code block()}으로 완료를 대기한다.
 */
final class NovaOrm implements OrmBenchmark {

    private final ConnectionFactory connectionFactory;
    private final ReactiveEntityOperations operations;
    private final SimpleSchemaInitializer schema;

    NovaOrm() {
        String url = "r2dbc:h2:mem:///novabench?DB_CLOSE_DELAY=-1";
        // Hibernate의 내장 풀(max 20)과 대등하게 r2dbc-pool로 커넥션을 재사용한다(연산당 커넥션 생성 비용 제거).
        ConnectionFactory base = ConnectionFactories.get(url);
        this.connectionFactory = new ConnectionPool(
                ConnectionPoolConfiguration.builder(base).initialSize(5).maxSize(20).build());
        Dialect dialect = new H2Dialect();
        EntityMetadataFactory metadataFactory = new EntityMetadataFactory(new DefaultNamingStrategy());
        R2dbcSqlExecutor executor = new R2dbcSqlExecutor(connectionFactory, dialect);
        R2dbcTransactionManager txManager = new R2dbcTransactionManager(connectionFactory);
        this.operations = new SimpleReactiveEntityOperations(
                metadataFactory, dialect, executor, new EntityStateDetector(), txManager);
        this.schema = new SimpleSchemaInitializer(operations, metadataFactory, dialect);
    }

    @Override
    public String name() {
        return "Nova (R2DBC, reactive)";
    }

    @Override
    public void setupSchema() {
        schema.create(BenchUser.class).block();
    }

    @Override
    public void clear() {
        // deleteAll은 non-null predicate를 요구하므로(전체 삭제 API 부재) 테이블을 재생성해 비운다.
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
            BenchUser user = operations.findById(BenchUser.class, id).block();
            if (user != null) {
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
        if (connectionFactory instanceof ConnectionPool pool) {
            pool.dispose();
        }
    }
}
