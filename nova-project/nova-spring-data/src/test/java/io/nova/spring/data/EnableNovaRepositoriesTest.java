package io.nova.spring.data;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
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
import reactor.core.publisher.Flux;
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

    @Test
    void jpqlAtQueryWorksThroughEnableNovaRepositories() {
        WidgetRepository repository = context.getBean(WidgetRepository.class);

        Mono<Widget> pipeline = repository.save(new Widget(null, "alpha"))
                .then(repository.save(new Widget(null, "beta")))
                .then(repository.jpqlByName("alpha"));

        StepVerifier.create(pipeline)
                .assertNext(w -> assertTrue(w.getName().equals("alpha")))
                .verifyComplete();

        StepVerifier.create(repository.save(new Widget(null, "zeta")).thenMany(repository.jpqlNames()))
                .expectNext("alpha", "beta", "zeta")
                .verifyComplete();
    }

    @Test
    void nativeAtQueryWorksThroughEnableNovaRepositories() {
        WidgetRepository repository = context.getBean(WidgetRepository.class);

        Mono<Widget> pipeline = repository.save(new Widget(null, "gamma"))
                .then(repository.nativeByName("gamma"));

        StepVerifier.create(pipeline)
                .assertNext(w -> {
                    assertNotNull(w.getId());
                    assertTrue(w.getName().equals("gamma"));
                })
                .verifyComplete();
    }

    @Test
    void modifyingAtQueryWorksThroughEnableNovaRepositories() {
        WidgetRepository repository = context.getBean(WidgetRepository.class);

        Mono<Long> pipeline = repository.save(new Widget(null, "old"))
                .then(repository.renameAll("old", "new"));

        StepVerifier.create(pipeline)
                .expectNext(1L)
                .verifyComplete();

        StepVerifier.create(repository.jpqlByName("new"))
                .assertNext(w -> assertTrue(w.getName().equals("new")))
                .verifyComplete();
    }

    @Entity
    @Table(name = "widgets")
    public static class Widget {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;

        @Column(name = "widget_name")
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

        // @EnableNovaRepositories 경로에서 별도 배선 없이 JpqlExecutor가 자동 구성되어야 동작한다.
        @Query("SELECT w FROM Widget w WHERE w.name = :name")
        Mono<Widget> jpqlByName(@Param("name") String name);

        @Query("SELECT w.name FROM Widget w ORDER BY w.name")
        Flux<String> jpqlNames();

        // native @Query는 Dialect가 자동 구성되어야 bind marker 렌더링이 동작한다.
        @Query(value = "SELECT * FROM \"widgets\" WHERE \"widget_name\" = :name", nativeQuery = true)
        Mono<Widget> nativeByName(@Param("name") String name);

        @Modifying
        @Query("UPDATE Widget w SET w.name = :to WHERE w.name = :from")
        Mono<Long> renameAll(@Param("from") String from, @Param("to") String to);
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
