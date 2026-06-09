package io.nova.boot;

import io.nova.Nova;
import io.nova.boot.ddlauto.DdlAutoBootstrapEntity;
import io.nova.core.ReactiveEntityOperations;
import io.nova.dialect.h2.H2Dialect;
import io.nova.query.QuerySpec;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * SchemaBootstrapRunner의 *효과*를 검증한다 — bean이 등록되었다는 것만으로는 dead config
 * 문제(NovaProperties.pool 회귀 메모와 동일한 패턴)를 잡지 못한다. 실제 H2 driver로 컨텍스트를
 * 부트한 뒤, 테이블이 만들어졌는지/CREATE_DROP의 destroy 단계가 동작하는지 모두 확인한다.
 */
class SchemaBootstrapRunnerEffectTest {

    /** 각 테스트가 독립된 in-memory DB를 보장 — 컨텍스트 close 후에도 DB가 살아 있도록 DB_CLOSE_DELAY=-1 사용. */
    private static final AtomicInteger DB_SEQ = new AtomicInteger();

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(NovaAutoConfiguration.class));

    @Test
    void createModeActuallyCreatesTablesDiscoveredByEntityPackages() {
        AtomicReference<ConnectionFactory> sharedCf = new AtomicReference<>(freshConnectionFactory());

        runner.withUserConfiguration(testInfrastructure(sharedCf.get()))
                .withPropertyValues(
                        "nova.ddl-auto=create",
                        "nova.entity-packages=io.nova.boot.ddlauto")
                .run(context -> {
                    assertNotNull(context.getStartupFailure() == null
                            ? context.getBean(SchemaBootstrapRunner.class) : null);

                    // 테이블이 실제로 만들어졌다면 save → findAll이 성공해야 한다.
                    ReactiveEntityOperations operations = context.getBean(ReactiveEntityOperations.class);
                    Long count = operations.save(new DdlAutoBootstrapEntity(null, "ddl-auto@example.com"))
                            .then(operations.count(DdlAutoBootstrapEntity.class, QuerySpec.empty()))
                            .block();
                    assertEquals(1L, count);
                });
    }

    @Test
    void createDropModeDropsTablesOnContextClose() {
        ConnectionFactory cf = freshConnectionFactory();

        // 1) ddl-auto=create-drop으로 컨텍스트 시작 → 테이블 생성 → 컨텍스트 close → drop 실행
        runner.withUserConfiguration(testInfrastructure(cf))
                .withPropertyValues(
                        "nova.ddl-auto=create-drop",
                        "nova.entity-packages=io.nova.boot.ddlauto")
                .run(context -> {
                    // 컨텍스트가 살아 있는 동안 테이블이 존재함을 확인 (insert 가능)
                    ReactiveEntityOperations operations = context.getBean(ReactiveEntityOperations.class);
                    operations.save(new DdlAutoBootstrapEntity(null, "drop-me@example.com")).block();
                });

        // 2) 컨텍스트가 close된 후 같은 DB에 별도 connection으로 접근해 보면 테이블이 사라져 있어야 한다.
        //    DB_CLOSE_DELAY=-1로 DB 자체는 살아 있지만 테이블은 SchemaBootstrapRunner.destroy()가 지웠어야 한다.
        ReactiveEntityOperations probe = Nova.create(cf);
        assertThrows(Throwable.class,
                () -> probe.count(DdlAutoBootstrapEntity.class, QuerySpec.empty()).block(),
                "table should be dropped on context close — select must fail");
    }

    @Test
    void noneModeDoesNotCreateAnyTable() {
        ConnectionFactory cf = freshConnectionFactory();

        runner.withUserConfiguration(testInfrastructure(cf))
                .withPropertyValues(
                        "nova.ddl-auto=none",
                        "nova.entity-packages=io.nova.boot.ddlauto")
                .run(context -> {
                    // ddl-auto=none이면 테이블이 만들어지지 않아야 하므로 select가 실패해야 한다.
                    ReactiveEntityOperations operations = context.getBean(ReactiveEntityOperations.class);
                    assertThrows(Throwable.class,
                            () -> operations.count(DdlAutoBootstrapEntity.class, QuerySpec.empty()).block(),
                            "ddl-auto=none should not create any table");
                });
    }

    private ConnectionFactory freshConnectionFactory() {
        int seq = DB_SEQ.incrementAndGet();
        return ConnectionFactories.get(
                "r2dbc:h2:mem:///ddlauto" + seq + "?options=DB_CLOSE_DELAY=-1");
    }

    private static Class<?> testInfrastructure(ConnectionFactory cf) {
        SharedInfrastructure.HOLDER.set(cf);
        return SharedInfrastructure.class;
    }

    /**
     * Spring 컨텍스트에 ConnectionFactory + H2 Dialect를 노출하기 위한 helper config.
     * {@code @Bean}이 instance를 제공해야 하므로 ThreadLocal 우회로 외부에서 주입한 cf를 꺼낸다 —
     * 테스트는 동기 단일 스레드라 안전하다.
     */
    @Configuration(proxyBeanMethods = false)
    static class SharedInfrastructure {
        static final ThreadLocal<ConnectionFactory> HOLDER = new ThreadLocal<>();

        @Bean
        ConnectionFactory connectionFactory() {
            ConnectionFactory cf = HOLDER.get();
            if (cf == null) {
                throw new IllegalStateException("SharedInfrastructure.HOLDER must be set before context start");
            }
            return cf;
        }

        @Bean
        io.nova.sql.Dialect dialect() {
            return new H2Dialect();
        }
    }
}
