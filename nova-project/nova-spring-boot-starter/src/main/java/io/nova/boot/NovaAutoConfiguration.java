package io.nova.boot;

import io.nova.Nova;
import io.nova.core.CompositeSqlExecutionListener;
import io.nova.core.EntityStateDetector;
import io.nova.core.ReactiveEntityOperations;
import io.nova.core.SimpleReactiveEntityOperations;
import io.nova.core.SlowQueryLoggingListener;
import io.nova.core.SqlExecutionListener;
import io.nova.core.SqlExecutor;
import io.nova.metadata.DefaultNamingStrategy;
import io.nova.metadata.EntityMetadataFactory;
import io.nova.metadata.NamingStrategy;
import io.nova.r2dbc.PoolConfig;
import io.nova.r2dbc.R2dbcSqlExecutor;
import io.nova.r2dbc.R2dbcTransactionManager;
import io.nova.schema.DdlAuto;
import io.nova.schema.SchemaInitializer;
import io.nova.schema.SimpleSchemaInitializer;
import io.nova.sql.Dialect;
import io.nova.tx.ReactiveTransactionManager;
import io.r2dbc.spi.ConnectionFactory;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.time.Duration;
import java.util.List;

/**
 * Nova starter의 Spring Boot auto-configuration. 사용자 컨텍스트에 {@link ConnectionFactory} 빈이
 * 있으면 동작하며, {@link Dialect}는 {@link Nova#resolveDialect(ConnectionFactory)}를 통해
 * ConnectionFactory의 driver 메타데이터({@code ConnectionFactoryMetadata.getName()})로 자동 감지된다.
 * 모든 빈은 {@code @ConditionalOnMissingBean}으로 보호되어 사용자가 직접 정의한 빈을 절대 덮어쓰지 않는다.
 * 특히 사용자가 {@link Dialect} 빈을 직접 등록하면 auto-detection 빈은 backoff하고 사용자 빈이 우선한다.
 */
@AutoConfiguration
@ConditionalOnClass({ConnectionFactory.class, Dialect.class})
@EnableConfigurationProperties(NovaProperties.class)
public class NovaAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public NamingStrategy novaNamingStrategy() {
        return new DefaultNamingStrategy();
    }

    @Bean
    @ConditionalOnMissingBean
    public EntityMetadataFactory novaEntityMetadataFactory(NamingStrategy namingStrategy) {
        return new EntityMetadataFactory(namingStrategy);
    }

    @Bean
    @ConditionalOnMissingBean
    public EntityStateDetector novaEntityStateDetector() {
        return new EntityStateDetector();
    }

    /**
     * {@link ConnectionFactory}의 driver 메타데이터로 {@link Dialect}를 자동 감지한다. 매핑은
     * {@link Nova#resolveDialect(ConnectionFactory)}에 위임해 단일 진실 공급원을 유지하며,
     * 매핑되지 않은 driver의 경우 해당 메서드가 {@link IllegalStateException}을 던진다.
     * {@code @ConditionalOnMissingBean}이므로 사용자가 직접 {@link Dialect} 빈을 등록하면
     * 이 빈은 backoff하고 사용자 빈이 채택된다.
     */
    @Bean
    @ConditionalOnMissingBean
    public Dialect novaDialect(ConnectionFactory connectionFactory) {
        return Nova.resolveDialect(connectionFactory);
    }

    @Bean
    @ConditionalOnMissingBean
    public ReactiveTransactionManager novaTransactionManager(ConnectionFactory connectionFactory) {
        return new R2dbcTransactionManager(connectionFactory);
    }

    @Bean
    @ConditionalOnMissingBean
    public SqlExecutor novaSqlExecutor(
            ConnectionFactory connectionFactory,
            Dialect dialect,
            ObjectProvider<SqlExecutionListener> listenerProvider) {
        List<SqlExecutionListener> listeners = listenerProvider.orderedStream().toList();
        SqlExecutionListener listener = listeners.isEmpty()
                ? SqlExecutionListener.NO_OP
                : CompositeSqlExecutionListener.of(listeners.toArray(SqlExecutionListener[]::new));
        return new R2dbcSqlExecutor(connectionFactory, dialect, listener);
    }

    @Bean
    @ConditionalOnMissingBean
    public ReactiveEntityOperations novaEntityOperations(
            EntityMetadataFactory metadataFactory,
            Dialect dialect,
            SqlExecutor sqlExecutor,
            EntityStateDetector entityStateDetector,
            ReactiveTransactionManager transactionManager) {
        return new SimpleReactiveEntityOperations(
                metadataFactory, dialect, sqlExecutor, entityStateDetector, transactionManager);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "nova.slow-query", name = "threshold-ms")
    public SlowQueryLoggingListener novaSlowQueryLoggingListener(NovaProperties properties) {
        Long thresholdMs = properties.getSlowQuery().getThresholdMs();
        return new SlowQueryLoggingListener(Duration.ofMillis(thresholdMs));
    }

    /**
     * {@link PoolConfig} bean을 항상 노출한다. {@code nova.pool.*}로 명시되지 않은 필드는
     * {@link PoolConfig#defaults()} 값을 채택하므로, 사용자는 이 bean을 받아 자신의
     * {@link ConnectionFactory} 빌더(예: {@code r2dbc-pool})에 전달할 수 있다. Nova는
     * pool 구현체를 번들하지 않고 설정값만 노출한다.
     */
    @Bean
    @ConditionalOnMissingBean
    public PoolConfig novaPoolConfig(NovaProperties properties) {
        NovaProperties.Pool pool = properties.getPool();
        PoolConfig defaults = PoolConfig.defaults();
        return new PoolConfig(
                pool.getInitialSize() != null ? pool.getInitialSize() : defaults.initialSize(),
                pool.getMaxSize() != null ? pool.getMaxSize() : defaults.maxSize(),
                pool.getMaxIdleTime() != null ? pool.getMaxIdleTime() : defaults.maxIdleTime(),
                pool.getAcquireTimeout() != null ? pool.getAcquireTimeout() : defaults.acquireTimeout());
    }

    /**
     * Always-on helper that lets users call {@code schemaInitializer.create(MyEntity.class)}
     * from anywhere in their code. Drop-in target for integration tests that want to
     * provision tables imperatively without going through {@code nova.ddl-auto}.
     */
    @Bean
    @ConditionalOnMissingBean
    public SchemaInitializer novaSchemaInitializer(
            ReactiveEntityOperations operations,
            EntityMetadataFactory metadataFactory,
            Dialect dialect) {
        return new SimpleSchemaInitializer(operations, metadataFactory, dialect);
    }

    /**
     * Runs the {@link DdlAuto} bootstrap on application startup. Only registered when
     * {@code nova.ddl-auto} is set to anything other than {@code none} so that the
     * runner does not appear in the context for the default (do-nothing) configuration.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "nova", name = "ddl-auto", havingValue = "create", matchIfMissing = false)
    public SchemaBootstrapRunner novaSchemaBootstrapRunnerCreate(
            SchemaInitializer schemaInitializer,
            NovaProperties properties,
            BeanFactory beanFactory) {
        return new SchemaBootstrapRunner(schemaInitializer, properties, beanFactory);
    }

    @Bean(name = "novaSchemaBootstrapRunner")
    @ConditionalOnMissingBean(SchemaBootstrapRunner.class)
    @ConditionalOnProperty(prefix = "nova", name = "ddl-auto", havingValue = "create-drop", matchIfMissing = false)
    public SchemaBootstrapRunner novaSchemaBootstrapRunnerCreateDrop(
            SchemaInitializer schemaInitializer,
            NovaProperties properties,
            BeanFactory beanFactory) {
        return new SchemaBootstrapRunner(schemaInitializer, properties, beanFactory);
    }
}
