package io.nova.boot;

import io.nova.core.ReactiveEntityOperations;
import io.nova.query.QuerySpec;
import io.nova.schema.SchemaInitializer;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.r2dbc.R2dbcTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

class NovaTransactionalIntegrationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    R2dbcTransactionManagerAutoConfiguration.class,
                    NovaAutoConfiguration.class,
                    TransactionAutoConfiguration.class))
            .withUserConfiguration(TransactionTestConfig.class);

    @Test
    void springTransactionalCommitsAndRollsBackNovaOperations() {
        runner.run(context -> {
            assertNull(context.getStartupFailure());
            assertInstanceOf(
                    org.springframework.r2dbc.connection.R2dbcTransactionManager.class,
                    context.getBean(ReactiveTransactionManager.class));

            SchemaInitializer schema = context.getBean(SchemaInitializer.class);
            ReactiveEntityOperations operations = context.getBean(ReactiveEntityOperations.class);
            TransactionalAccountService service = context.getBean(TransactionalAccountService.class);

            StepVerifier.create(schema.create(TransactionalAccount.class)).verifyComplete();

            StepVerifier.create(service.createPair("first@example.com", "second@example.com"))
                    .expectNext(2L)
                    .verifyComplete();
            StepVerifier.create(operations.count(TransactionalAccount.class, QuerySpec.empty()))
                    .expectNext(2L)
                    .verifyComplete();

            StepVerifier.create(service.createPairThenFail("third@example.com", "fourth@example.com"))
                    .expectErrorMatches(error -> error instanceof IllegalStateException
                            && error.getMessage().equals("force rollback"))
                    .verify();
            StepVerifier.create(operations.count(TransactionalAccount.class, QuerySpec.empty()))
                    .expectNext(2L)
                    .verifyComplete();

            StepVerifier.create(operations.inTransaction(transactionalOperations ->
                            transactionalOperations.save(new TransactionalAccount("native@example.com"))
                                    .then(Mono.error(new IllegalStateException("force native rollback")))))
                    .expectErrorMatches(error -> error instanceof IllegalStateException
                            && error.getMessage().equals("force native rollback"))
                    .verify();
            StepVerifier.create(operations.count(TransactionalAccount.class, QuerySpec.empty()))
                    .expectNext(2L)
                    .verifyComplete();
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class TransactionTestConfig {
        @Bean
        ConnectionFactory connectionFactory() {
            return ConnectionFactories.get(
                    "r2dbc:h2:mem:///nova-spring-transactional-test;DB_CLOSE_DELAY=-1");
        }

        @Bean
        TransactionalAccountService transactionalAccountService(ReactiveEntityOperations operations) {
            return new TransactionalAccountService(operations);
        }
    }

    static class TransactionalAccountService {
        private final ReactiveEntityOperations operations;

        TransactionalAccountService(ReactiveEntityOperations operations) {
            this.operations = operations;
        }

        @Transactional
        public Mono<Long> createPair(String firstEmail, String secondEmail) {
            return operations.save(new TransactionalAccount(firstEmail))
                    .then(operations.save(new TransactionalAccount(secondEmail)))
                    .thenReturn(2L);
        }

        @Transactional
        public Mono<Void> createPairThenFail(String firstEmail, String secondEmail) {
            return operations.save(new TransactionalAccount(firstEmail))
                    .then(operations.save(new TransactionalAccount(secondEmail)))
                    .then(Mono.error(new IllegalStateException("force rollback")));
        }
    }

    @Entity
    @Table(name = "transactional_accounts")
    static class TransactionalAccount {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;

        @Column(nullable = false)
        private String email;

        TransactionalAccount() {
        }

        TransactionalAccount(String email) {
            this.email = email;
        }
    }
}
