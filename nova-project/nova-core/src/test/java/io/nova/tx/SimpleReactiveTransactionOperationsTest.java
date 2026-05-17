package io.nova.tx;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SimpleReactiveTransactionOperationsTest {
    @Test
    void commitsOnSuccessfulCallback() {
        RecordingTransactionManager manager = new RecordingTransactionManager();
        SimpleReactiveTransactionOperations operations = new SimpleReactiveTransactionOperations(manager);

        StepVerifier.create(operations.inTransaction(context -> Mono.just("ok")))
                .expectNext("ok")
                .verifyComplete();

        assertEquals(List.of("begin", "commit"), manager.events);
    }

    @Test
    void rollsBackOnCallbackFailure() {
        RecordingTransactionManager manager = new RecordingTransactionManager();
        SimpleReactiveTransactionOperations operations = new SimpleReactiveTransactionOperations(manager);

        StepVerifier.create(operations.inTransaction(context -> Mono.error(new IllegalStateException("boom"))))
                .expectErrorMessage("boom")
                .verify();

        assertEquals(List.of("begin", "rollback"), manager.events);
    }

    @Test
    void rollsBackWhenCommitFails() {
        RecordingTransactionManager manager = new RecordingTransactionManager();
        manager.commitFailure = new IllegalStateException("commit failed");
        SimpleReactiveTransactionOperations operations = new SimpleReactiveTransactionOperations(manager);

        StepVerifier.create(operations.inTransaction(context -> Mono.just("ok")))
                .expectErrorMessage("commit failed")
                .verify();

        assertEquals(List.of("begin", "commit", "rollback"), manager.events);
    }

    @Test
    void surfacesRollbackFailureWhenRollbackAlsoFails() {
        RecordingTransactionManager manager = new RecordingTransactionManager();
        manager.callbackFailure = new IllegalStateException("callback failed");
        manager.rollbackFailure = new IllegalStateException("rollback failed");
        SimpleReactiveTransactionOperations operations = new SimpleReactiveTransactionOperations(manager);

        StepVerifier.create(operations.inTransaction(context -> Mono.error(manager.callbackFailure)))
                .expectErrorMessage("rollback failed")
                .verify();

        assertEquals(List.of("begin", "rollback"), manager.events);
    }

    private static final class RecordingTransactionManager implements ReactiveTransactionManager {
        private final List<String> events = new ArrayList<>();
        private RuntimeException commitFailure;
        private RuntimeException rollbackFailure;
        private RuntimeException callbackFailure;

        @Override
        public <T> Mono<T> inTransaction(java.util.function.Function<TransactionContext, Mono<T>> callback) {
            return begin().flatMap(callback);
        }

        @Override
        public Mono<TransactionContext> begin() {
            events.add("begin");
            return Mono.just(() -> "tx");
        }

        @Override
        public Mono<Void> commit(TransactionContext context) {
            events.add("commit");
            if (commitFailure != null) {
                return Mono.error(commitFailure);
            }
            return Mono.empty();
        }

        @Override
        public Mono<Void> rollback(TransactionContext context) {
            events.add("rollback");
            if (rollbackFailure != null) {
                return Mono.error(rollbackFailure);
            }
            return Mono.empty();
        }
    }
}
