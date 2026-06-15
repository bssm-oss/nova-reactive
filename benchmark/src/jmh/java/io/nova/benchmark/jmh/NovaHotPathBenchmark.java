package io.nova.benchmark.jmh;

import io.nova.benchmark.entity.BenchUser;
import io.nova.core.EntityStateDetector;
import io.nova.core.ReactiveEntityOperations;
import io.nova.core.SimpleReactiveEntityOperations;
import io.nova.dialect.h2.H2Dialect;
import io.nova.metadata.DefaultNamingStrategy;
import io.nova.metadata.EntityMetadata;
import io.nova.metadata.EntityMetadataFactory;
import io.nova.query.QuerySpec;
import io.nova.r2dbc.R2dbcSqlExecutor;
import io.nova.r2dbc.R2dbcTransactionManager;
import io.nova.schema.SimpleSchemaInitializer;
import io.nova.sql.Dialect;
import io.nova.sql.SqlRenderer;
import io.nova.sql.SqlStatement;
import io.r2dbc.pool.ConnectionPool;
import io.r2dbc.pool.ConnectionPoolConfiguration;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Nova 핫패스의 JMH 정밀 측정(H2 in-memory). coarse 하니스의 run 노이즈 없이 ns/op를 본다.
 * <ul>
 *   <li>{@code renderSelectById} — DB 없이 SELECT SQL 렌더 비용만(= SQL 캐시가 절감하는 양).</li>
 *   <li>{@code findById} — 단건 조회 end-to-end(렌더+커넥션 acquire+reactive+row 매핑).</li>
 *   <li>{@code findAll100} — 100 row 조회(엔티티 생성+필드 write 비중 큼).</li>
 *   <li>{@code saveInsert} — 단건 INSERT.</li>
 * </ul>
 * 각 @Benchmark는 별도 trial이라 @Setup이 새로 스키마를 만들고 100 row를 seed한다(상호 오염 없음).
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class NovaHotPathBenchmark {

    private ConnectionPool connectionFactory;
    private ReactiveEntityOperations operations;
    private Dialect dialect;
    private SqlRenderer renderer;
    private EntityMetadata<BenchUser> metadata;
    private Long oneId;

    @Setup(Level.Trial)
    public void setup() {
        String url = "r2dbc:h2:mem:///jmh_" + UUID.randomUUID().toString().replace("-", "") + "?DB_CLOSE_DELAY=-1";
        ConnectionFactory base = ConnectionFactories.get(url);
        this.connectionFactory = new ConnectionPool(
                ConnectionPoolConfiguration.builder(base).initialSize(4).maxSize(20).build());
        this.dialect = new H2Dialect();
        this.renderer = dialect.sqlRenderer();
        EntityMetadataFactory metadataFactory = new EntityMetadataFactory(new DefaultNamingStrategy());
        this.metadata = metadataFactory.getEntityMetadata(BenchUser.class);
        R2dbcSqlExecutor executor = new R2dbcSqlExecutor(connectionFactory, dialect);
        R2dbcTransactionManager txManager = new R2dbcTransactionManager(connectionFactory);
        this.operations = new SimpleReactiveEntityOperations(
                metadataFactory, dialect, executor, new EntityStateDetector(), txManager);
        new SimpleSchemaInitializer(operations, metadataFactory, dialect).create(BenchUser.class).block();

        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            ids.add(operations.save(new BenchUser("user" + i, "user" + i + "@nova.io", 20 + i)).block().getId());
        }
        this.oneId = ids.get(50);
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        connectionFactory.dispose();
    }

    /** DB를 치지 않는 순수 SQL 렌더 비용 — SQL 캐시(현재 findById에 적용)가 제거하는 부분이다. */
    @Benchmark
    public SqlStatement renderSelectById() {
        return renderer.selectById(metadata, oneId);
    }

    @Benchmark
    public BenchUser findById() {
        return operations.findById(BenchUser.class, oneId).block();
    }

    @Benchmark
    public List<BenchUser> findAll100() {
        return operations.findAll(BenchUser.class, QuerySpec.empty()).collectList().block();
    }

    @Benchmark
    public BenchUser saveInsert() {
        return operations.save(new BenchUser("x", "x@nova.io", 30)).block();
    }
}
