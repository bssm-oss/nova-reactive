package io.nova.spring.data;

import io.nova.annotation.Column;
import io.nova.annotation.Entity;
import io.nova.annotation.GeneratedValue;
import io.nova.annotation.GenerationType;
import io.nova.annotation.Id;
import io.nova.annotation.Table;
import io.nova.core.EntityStateDetector;
import io.nova.core.ReactiveEntityOperations;
import io.nova.core.SimpleReactiveEntityOperations;
import io.nova.dialect.h2.H2Dialect;
import io.nova.metadata.DefaultNamingStrategy;
import io.nova.metadata.EntityMetadataFactory;
import io.nova.r2dbc.R2dbcSqlExecutor;
import io.nova.r2dbc.R2dbcTransactionManager;
import io.nova.sql.Dialect;
import io.nova.sql.SqlStatement;
import io.nova.tx.SimpleReactiveTransactionOperations;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link EnableNovaRepositories}로 스캔된 repository proxy가 실제 {@link AnnotationConfigApplicationContext}
 * 안에서 H2에 round-trip save/findById 까지 작동하는지 검증한다.
 *
 * <p>{@code feedback_spring_config_dead_test.md} 메모리에 따라 binder 검증만으로는 dead config
 * 누락을 잡을 수 없으므로 실제 Spring 컨테이너 + H2 ConnectionFactory + 실제 DDL + 실제 INSERT/SELECT
 * 흐름을 그대로 부트스트랩한다.
 */
class EnableNovaRepositoriesTest {

    private AnnotationConfigApplicationContext context;
    private String dbName;

    @BeforeEach
    void setUp() {
        dbName = "novaspd_" + UUID.randomUUID().toString().replace("-", "");
        System.setProperty("nova.test.db", dbName);
        context = new AnnotationConfigApplicationContext();
        context.register(TestConfig.class);
        context.refresh();
    }

    @AfterEach
    void tearDown() {
        if (context != null) {
            context.close();
        }
        System.clearProperty("nova.test.db");
    }

    @Test
    void scanRegistersRepositoryProxyBean() {
        assertTrue(context.containsBean("widgetRepository"),
                "lowercased simple name should be the bean name");
        WidgetRepository repository = context.getBean(WidgetRepository.class);
        assertNotNull(repository);
        assertSame(repository, context.getBean("widgetRepository"),
                "bean lookup by name and type must return the same proxy");
    }

    @Test
    void saveAndFindByIdRoundTripThroughRepositoryProxy() {
        WidgetRepository repository = context.getBean(WidgetRepository.class);
        Widget widget = new Widget(null, "alpha");

        Mono<Widget> roundTrip = repository.save(widget)
                .flatMap(saved -> {
                    assertNotNull(saved.getId(), "IDENTITY id must be assigned after save");
                    return repository.findById(saved.getId());
                });

        StepVerifier.create(roundTrip)
                .assertNext(loaded -> {
                    assertNotNull(loaded);
                    assertNotNull(loaded.getId());
                    assertTrue(loaded.getName().equals("alpha"),
                            "loaded entity name must match what we saved, got: " + loaded.getName());
                })
                .verifyComplete();
    }

    @Test
    void countReflectsInsertedRows() {
        WidgetRepository repository = context.getBean(WidgetRepository.class);

        Mono<Long> pipeline = repository.save(new Widget(null, "first"))
                .then(repository.save(new Widget(null, "second")))
                .then(repository.count());

        StepVerifier.create(pipeline)
                .expectNext(2L)
                .verifyComplete();
    }

    @Entity
    @Table("widgets")
    public static class Widget {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;

        @Column("widget_name")
        private String name;

        public Widget() {
        }

        Widget(Long id, String name) {
            this.id = id;
            this.name = name;
        }

        public Long getId() {
            return id;
        }

        public String getName() {
            return name;
        }
    }

    public interface WidgetRepository extends ReactiveCrudRepository<Widget, Long> {
    }

    @Configuration(proxyBeanMethods = false)
    @EnableNovaRepositories(basePackageClasses = WidgetRepository.class)
    static class TestConfig {

        @Bean
        ConnectionFactory connectionFactory() {
            String db = System.getProperty("nova.test.db");
            return ConnectionFactories.get("r2dbc:h2:mem:///" + db + "?DB_CLOSE_DELAY=-1");
        }

        @Bean
        Dialect dialect() {
            return new H2Dialect();
        }

        @Bean
        EntityMetadataFactory entityMetadataFactory() {
            return new EntityMetadataFactory(new DefaultNamingStrategy());
        }

        @Bean
        EntityStateDetector entityStateDetector() {
            return new EntityStateDetector();
        }

        @Bean
        R2dbcSqlExecutor sqlExecutor(ConnectionFactory connectionFactory, Dialect dialect) {
            return new R2dbcSqlExecutor(connectionFactory, dialect);
        }

        @Bean
        R2dbcTransactionManager transactionManager(ConnectionFactory connectionFactory) {
            return new R2dbcTransactionManager(connectionFactory);
        }

        @Bean
        SimpleReactiveTransactionOperations transactionOperations(R2dbcTransactionManager transactionManager) {
            return new SimpleReactiveTransactionOperations(transactionManager);
        }

        @Bean
        ReactiveEntityOperations novaEntityOperations(
                EntityMetadataFactory metadataFactory,
                Dialect dialect,
                R2dbcSqlExecutor sqlExecutor,
                EntityStateDetector entityStateDetector,
                SimpleReactiveTransactionOperations transactionOperations
        ) {
            SimpleReactiveEntityOperations operations = new SimpleReactiveEntityOperations(
                    metadataFactory, dialect, sqlExecutor, entityStateDetector, transactionOperations);
            // 스키마는 dialect가 생성한 DDL을 그대로 H2에 실행한다 — 운영 DDL 경로를 우회하지 않는다.
            String ddl = operations.createTableSql(Widget.class);
            StepVerifier.create(sqlExecutor.execute(new SqlStatement(ddl, List.of())))
                    .expectNextCount(1)
                    .verifyComplete();
            return operations;
        }
    }
}
