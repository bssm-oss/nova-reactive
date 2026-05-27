package io.nova.boot;

import io.nova.Nova;
import io.nova.core.ReactiveEntityOperations;
import io.nova.core.SlowQueryLoggingListener;
import io.nova.core.SqlExecutor;
import io.nova.dialect.postgresql.PostgresqlDialect;
import io.nova.r2dbc.PoolConfig;
import io.nova.sql.BindMarkerStrategy;
import io.nova.sql.Dialect;
import io.nova.sql.SchemaGenerator;
import io.nova.sql.SqlRenderer;
import io.nova.tx.ReactiveTransactionManager;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NovaAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(NovaAutoConfiguration.class))
            .withUserConfiguration(BaseInfrastructureConfig.class);

    @Test
    void autoConfiguresCoreBeansWhenConnectionFactoryAndDialectPresent() {
        runner.run(context -> {
            assertFalse(context.getStartupFailure() != null,
                    "context should start up without failure");
            assertTrue(context.containsBean("novaSqlExecutor"));
            assertTrue(context.containsBean("novaEntityOperations"));
            assertTrue(context.containsBean("novaTransactionManager"));
            assertNotNull(context.getBean(SqlExecutor.class));
            assertNotNull(context.getBean(ReactiveEntityOperations.class));
            assertNotNull(context.getBean(ReactiveTransactionManager.class));
        });
    }

    @Test
    void autoDetectsDialectFromConnectionFactoryWhenNoDialectBeanProvided() {
        // ConnectionFactory만 제공하고 Dialect 빈은 일부러 제공하지 않는다.
        // 이 경우에도 컨텍스트가 기동하고, novaDialect 빈이 driver 메타데이터로 자동 감지돼야 한다.
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(NovaAutoConfiguration.class))
                .withUserConfiguration(ConnectionFactoryOnlyConfig.class)
                .run(context -> {
                    assertNull(context.getStartupFailure(),
                            "context should start without a user-provided Dialect bean");
                    assertTrue(context.containsBean("novaDialect"),
                            "auto-detection bean should be registered when no Dialect bean exists");

                    Dialect dialect = context.getBean(Dialect.class);
                    assertNotNull(dialect, "auto-detected Dialect bean must be present");
                    assertNotNull(dialect.name(), "auto-detected Dialect must be a usable dialect");

                    // 단일 진실 공급원 검증: starter 빈은 Nova.resolveDialect와 동일한 dialect 타입을 산출해야 한다.
                    // (현재 base의 H2 매핑이 무엇이든, 다른 worktree의 resolveDialect 확장 이후에도 일관되게 동작.)
                    ConnectionFactory cf = context.getBean(ConnectionFactory.class);
                    Class<?> expected = Nova.resolveDialect(cf).getClass();
                    assertEquals(expected, dialect.getClass(),
                            "auto-detected Dialect must match Nova.resolveDialect for the same ConnectionFactory");
                });
    }

    @Test
    void autoDetectedDialectDrivesFullEntityOperationsGraph() {
        // auto-detection 빈이 dead config가 아님을 증명: Dialect 빈 없이도 SqlExecutor/
        // ReactiveEntityOperations까지 전체 빈 그래프가 조립돼야 한다.
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(NovaAutoConfiguration.class))
                .withUserConfiguration(ConnectionFactoryOnlyConfig.class)
                .run(context -> {
                    assertNull(context.getStartupFailure(),
                            "full bean graph must assemble from an auto-detected Dialect");
                    assertNotNull(context.getBean(Dialect.class));
                    assertNotNull(context.getBean(SqlExecutor.class));
                    assertNotNull(context.getBean(ReactiveEntityOperations.class));
                    assertNotNull(context.getBean(ReactiveTransactionManager.class));
                });
    }

    @Test
    void userDefinedDialectWinsOverAutoDetection() {
        // 사용자가 커스텀 Dialect 빈을 등록하면 @ConditionalOnMissingBean으로 novaDialect가 backoff해야 한다.
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(NovaAutoConfiguration.class))
                .withUserConfiguration(ConnectionFactoryOnlyConfig.class, UserDialectConfig.class)
                .run(context -> {
                    assertFalse(context.containsBean("novaDialect"),
                            "auto-detection bean must back off when a user Dialect bean exists");
                    Dialect dialect = context.getBean(Dialect.class);
                    assertSame(UserDialectConfig.USER_DIALECT, dialect,
                            "user-defined Dialect must win over auto-detection");
                });
    }

    @Test
    void backsOffWhenUserDefinesOwnSqlExecutor() {
        runner.withUserConfiguration(UserSqlExecutorConfig.class).run(context -> {
            SqlExecutor executor = context.getBean(SqlExecutor.class);
            assertSame(UserSqlExecutorConfig.USER_EXECUTOR, executor,
                    "user-defined SqlExecutor must win over auto-configured bean");
        });
    }

    @Test
    void slowQueryListenerAbsentWhenThresholdNotConfigured() {
        runner.run(context -> {
            assertNull(context.getBeanProvider(SlowQueryLoggingListener.class).getIfAvailable(),
                    "no SlowQueryLoggingListener should be registered without nova.slow-query.threshold-ms");
        });
    }

    @Test
    void slowQueryListenerRegisteredWhenThresholdConfigured() {
        runner.withPropertyValues("nova.slow-query.threshold-ms=500").run(context -> {
            SlowQueryLoggingListener listener = context.getBean(SlowQueryLoggingListener.class);
            assertNotNull(listener);
            assertEquals(Duration.ofMillis(500), listener.threshold());
        });
    }

    @Test
    void propertiesBindFromConfigurationKeys() {
        runner.withPropertyValues(
                "nova.pool.initial-size=4",
                "nova.pool.max-size=42",
                "nova.pool.acquire-timeout=PT15S",
                "nova.pool.max-idle-time=PT20M",
                "nova.slow-query.threshold-ms=750"
        ).run(context -> {
            NovaProperties properties = context.getBean(NovaProperties.class);
            assertEquals(Integer.valueOf(4), properties.getPool().getInitialSize());
            assertEquals(Integer.valueOf(42), properties.getPool().getMaxSize());
            assertEquals(Duration.ofSeconds(15), properties.getPool().getAcquireTimeout());
            assertEquals(Duration.ofMinutes(20), properties.getPool().getMaxIdleTime());
            assertEquals(Long.valueOf(750), properties.getSlowQuery().getThresholdMs());
        });
    }

    @Test
    void poolConfigBeanReflectsConfiguredProperties() {
        runner.withPropertyValues(
                "nova.pool.initial-size=2",
                "nova.pool.max-size=20",
                "nova.pool.acquire-timeout=PT10S",
                "nova.pool.max-idle-time=PT5M"
        ).run(context -> {
            PoolConfig config = context.getBean(PoolConfig.class);
            assertEquals(2, config.initialSize());
            assertEquals(20, config.maxSize());
            assertEquals(Duration.ofSeconds(10), config.acquireTimeout());
            assertEquals(Duration.ofMinutes(5), config.maxIdleTime());
        });
    }

    @Test
    void poolConfigBeanFallsBackToDefaultsWhenPropertiesAbsent() {
        runner.run(context -> {
            PoolConfig config = context.getBean(PoolConfig.class);
            PoolConfig defaults = PoolConfig.defaults();
            assertEquals(defaults.initialSize(), config.initialSize());
            assertEquals(defaults.maxSize(), config.maxSize());
            assertEquals(defaults.acquireTimeout(), config.acquireTimeout());
            assertEquals(defaults.maxIdleTime(), config.maxIdleTime());
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class BaseInfrastructureConfig {
        @Bean
        ConnectionFactory connectionFactory() {
            return ConnectionFactories.get("r2dbc:h2:mem:///nova-starter-test;DB_CLOSE_DELAY=-1");
        }

        @Bean
        Dialect dialect() {
            return new PostgresqlDialect();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class ConnectionFactoryOnlyConfig {
        @Bean
        ConnectionFactory connectionFactory() {
            return ConnectionFactories.get("r2dbc:h2:mem:///nova-autodetect-test;DB_CLOSE_DELAY=-1");
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class UserDialectConfig {
        static final Dialect USER_DIALECT = new StubDialect();

        @Bean
        Dialect dialect() {
            return USER_DIALECT;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class UserSqlExecutorConfig {
        static final SqlExecutor USER_EXECUTOR = new StubSqlExecutor();

        @Bean
        SqlExecutor sqlExecutor() {
            return USER_EXECUTOR;
        }
    }

    /**
     * 정체성 비교 전용 stub Dialect. {@code @ConditionalOnMissingBean} backoff 검증에만 쓰이며
     * SQL을 렌더하지 않으므로 renderer/generator는 노출하지 않는다.
     */
    private static final class StubDialect implements Dialect {
        @Override
        public String name() {
            return "stub";
        }

        @Override
        public String quote(String identifier) {
            return "\"" + identifier + "\"";
        }

        @Override
        public BindMarkerStrategy bindMarkers() {
            return index -> "?";
        }

        @Override
        public SqlRenderer sqlRenderer() {
            throw new UnsupportedOperationException("stub dialect renders no SQL");
        }

        @Override
        public SchemaGenerator schemaGenerator() {
            throw new UnsupportedOperationException("stub dialect generates no schema");
        }
    }

    private static final class StubSqlExecutor implements SqlExecutor {
        @Override
        public reactor.core.publisher.Mono<Long> execute(io.nova.sql.SqlStatement statement) {
            return reactor.core.publisher.Mono.just(0L);
        }

        @Override
        public reactor.core.publisher.Mono<Long> executeBatch(String sql, java.util.List<java.util.List<Object>> bindingsList) {
            return reactor.core.publisher.Mono.just(0L);
        }

        @Override
        public <T> reactor.core.publisher.Mono<T> queryOne(io.nova.sql.SqlStatement statement,
                                                            java.util.function.Function<io.nova.core.RowAccessor, T> mapper) {
            return reactor.core.publisher.Mono.empty();
        }

        @Override
        public <T> reactor.core.publisher.Flux<T> queryMany(io.nova.sql.SqlStatement statement,
                                                             java.util.function.Function<io.nova.core.RowAccessor, T> mapper) {
            return reactor.core.publisher.Flux.empty();
        }

        @Override
        public <T> reactor.core.publisher.Flux<T> executeBatchAndReturnGeneratedKeys(
                String sql, java.util.List<java.util.List<Object>> bindingsList, String idColumn, Class<T> idType) {
            return reactor.core.publisher.Flux.empty();
        }

        @Override
        public <T> reactor.core.publisher.Mono<T> executeAndReturnGeneratedKey(
                io.nova.sql.SqlStatement statement, String idColumn, Class<T> idType) {
            return reactor.core.publisher.Mono.empty();
        }
    }
}
