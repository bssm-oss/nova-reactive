package io.nova.tx;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SimpleReactiveTransactionOperationsTest {
    @Test
    void delegatesDefinitionAndCallbackLazilyToManagerBoundary() {
        RecordingTransactionManager manager = new RecordingTransactionManager();
        SimpleReactiveTransactionOperations operations = new SimpleReactiveTransactionOperations(manager);
        TransactionDefinition definition = TransactionDefinition.requiresNew();
        TransactionContext context = () -> "manager-context";
        manager.context = context;

        Mono<String> transaction = operations.inTransaction(definition,
                received -> Mono.just((String) received.resource()));

        assertEquals(0, manager.inTransactionCalls.get());

        StepVerifier.create(transaction)
                .expectNext("manager-context")
                .verifyComplete();

        assertEquals(1, manager.inTransactionCalls.get());
        assertSame(definition, manager.definition);
        assertEquals(1, manager.callbackCalls.get());
    }

    @Test
    void preservesManagerReactiveFailure() {
        RecordingTransactionManager manager = new RecordingTransactionManager();
        SimpleReactiveTransactionOperations operations = new SimpleReactiveTransactionOperations(manager);
        IllegalStateException failure = new IllegalStateException("manager failure");
        manager.failure = failure;

        StepVerifier.create(operations.inTransaction(context -> Mono.just("ok")))
                .expectErrorSatisfies(error -> assertSame(failure, error))
                .verify();

        assertEquals(1, manager.inTransactionCalls.get());
    }

    @Test
    void signalsUnsupportedOperationWhenManagerDoesNotProvideBoundary() {
        SimpleReactiveTransactionOperations operations =
                new SimpleReactiveTransactionOperations(new LegacyTransactionManager());
        AtomicInteger callbacks = new AtomicInteger();

        StepVerifier.create(operations.inTransaction(context -> {
                    callbacks.incrementAndGet();
                    return Mono.just("outside-manager-context");
                }))
                .expectError(UnsupportedOperationException.class)
                .verify();

        assertEquals(0, callbacks.get());
    }

    @Test
    void rejectsNullInputs() {
        SimpleReactiveTransactionOperations operations =
                new SimpleReactiveTransactionOperations(new RecordingTransactionManager());

        assertThrows(NullPointerException.class, () -> operations.inTransaction(null, context -> Mono.empty()));
        assertThrows(NullPointerException.class,
                () -> operations.inTransaction(TransactionDefinition.DEFAULT, null));
    }

    private static final class RecordingTransactionManager implements ReactiveTransactionManager {
        private final AtomicInteger inTransactionCalls = new AtomicInteger();
        private final AtomicInteger callbackCalls = new AtomicInteger();
        private TransactionDefinition definition;
        private TransactionContext context = () -> "tx";
        private RuntimeException failure;

        @Override
        public Mono<TransactionContext> begin() {
            return Mono.error(new AssertionError("begin must be owned by inTransaction"));
        }

        @Override
        public Mono<Void> commit(TransactionContext context) {
            return Mono.error(new AssertionError("commit must be owned by inTransaction"));
        }

        @Override
        public Mono<Void> rollback(TransactionContext context) {
            return Mono.error(new AssertionError("rollback must be owned by inTransaction"));
        }

        @Override
        public <T> Mono<T> inTransaction(TransactionDefinition definition,
                                         Function<TransactionContext, Mono<T>> callback) {
            inTransactionCalls.incrementAndGet();
            this.definition = definition;
            if (failure != null) {
                return Mono.error(failure);
            }
            return Mono.defer(() -> {
                callbackCalls.incrementAndGet();
                return callback.apply(context);
            });
        }
    }

    private static final class LegacyTransactionManager implements ReactiveTransactionManager {
        @Override
        public Mono<TransactionContext> begin() {
            return Mono.empty();
        }

        @Override
        public Mono<Void> commit(TransactionContext context) {
            return Mono.empty();
        }

        @Override
        public Mono<Void> rollback(TransactionContext context) {
            return Mono.empty();
        }
    }
}
