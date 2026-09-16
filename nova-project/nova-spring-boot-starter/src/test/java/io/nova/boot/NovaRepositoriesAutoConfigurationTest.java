package io.nova.boot;

import io.nova.boot.autorepository.AutoRepositoryBook;
import io.nova.boot.autorepository.AutoRepositoryBookRepository;
import io.nova.boot.autorepository.AutoRepositoryTestApplication;
import io.nova.schema.SchemaInitializer;
import io.nova.spring.data.EnableNovaRepositories;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.test.StepVerifier;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class NovaRepositoriesAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    NovaAutoConfiguration.class,
                    NovaRepositoriesAutoConfiguration.class))
            .withUserConfiguration(AutoRepositoryTestApplication.class, TestInfrastructureConfig.class);

    @Test
    void autoDiscoversRepositoryFromBootApplicationPackage() {
        runner.run(context -> {
            assertNull(context.getStartupFailure());
            assertNotNull(context.getBean(AutoRepositoryBookRepository.class));
            assertSame(
                    context.getBean(AutoRepositoryBookRepository.class),
                    context.getBean("autoRepositoryBookRepository"));

            SchemaInitializer schemaInitializer = context.getBean(SchemaInitializer.class);
            AutoRepositoryBookRepository repository = context.getBean(AutoRepositoryBookRepository.class);

            StepVerifier.create(schemaInitializer.create(AutoRepositoryBook.class)
                            .then(repository.save(new AutoRepositoryBook("Reactive persistence")))
                            .flatMap(saved -> repository.findByTitle(saved.getTitle())))
                    .assertNext(book -> {
                        assertNotNull(book.getId());
                        assertEquals("Reactive persistence", book.getTitle());
                    })
                    .verifyComplete();
        });
    }

    @Test
    void repositoryAutoDiscoveryCanBeDisabled() {
        runner.withPropertyValues("nova.repositories.enabled=false")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertFalse(context.containsBean("autoRepositoryBookRepository"));
                    assertEquals(0, context.getBeanNamesForType(AutoRepositoryBookRepository.class).length);
                });
    }

    @Test
    void explicitEnableAnnotationOwnsRepositoryRegistration() {
        runner.withUserConfiguration(ExplicitRepositoryConfig.class)
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertEquals(1, context.getBeanNamesForType(AutoRepositoryBookRepository.class).length);
                    assertNotNull(context.getBean(AutoRepositoryBookRepository.class));
                });
    }

    @Test
    void existingUserBeanWithConventionalNameWins() {
        runner.withUserConfiguration(UserRepositoryNameConfig.class)
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertSame(
                            UserRepositoryNameConfig.USER_BEAN,
                            context.getBean("autoRepositoryBookRepository"));
                    assertEquals(0, context.getBeanNamesForType(AutoRepositoryBookRepository.class).length);
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class TestInfrastructureConfig {

        @Bean
        ConnectionFactory connectionFactory() {
            String databaseName = "nova-auto-repository-"
                    + UUID.randomUUID().toString().replace("-", "");
            return ConnectionFactories.get("r2dbc:h2:mem:///" + databaseName + ";DB_CLOSE_DELAY=-1");
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableNovaRepositories(basePackageClasses = AutoRepositoryBookRepository.class)
    static class ExplicitRepositoryConfig {
    }

    @Configuration(proxyBeanMethods = false)
    static class UserRepositoryNameConfig {

        static final Object USER_BEAN = new Object();

        @Bean
        Object autoRepositoryBookRepository() {
            return USER_BEAN;
        }
    }
}
