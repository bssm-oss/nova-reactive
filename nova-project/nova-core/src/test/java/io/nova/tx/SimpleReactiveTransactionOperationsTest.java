package io.nova.tx;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class SimpleReactiveTransactionOperationsTest {
    @Test
    void defersBeginUntilSubscription() {
        RecordingTransactionManager manager = new RecordingTransactionManager();
        SimpleReactiveTransactionOperations operations = new SimpleReactiveTransactionOperations(manager);

        Mono<String> transaction = operations.inTransaction(context -> Mono.just("ok"));

        assertEquals(List.of(), manager.events);

        StepVerifier.create(transaction)
                .expectNext("ok")
                .verifyComplete();

        assertEquals(List.of("begin", "commit"), manager.events);
    }

    @Test
    void commitsAfterAnEmptyCallback() {
        RecordingTransactionManager manager = new RecordingTransactionManager();
        SimpleReactiveTransactionOperations operations = new SimpleReactiveTransactionOperations(manager);

        StepVerifier.create(operations.inTransaction(context -> Mono.empty()))
                .verifyComplete();

        assertEquals(List.of("begin", "commit"), manager.events);
    }

    @Test
    void rollsBackOnSynchronousCallbackFailure() {
        RecordingTransactionManager manager = new RecordingTransactionManager();
        SimpleReactiveTransactionOperations operations = new SimpleReactiveTransactionOperations(manager);
        IllegalStateException failure = new IllegalStateException("boom");

        StepVerifier.create(operations.inTransaction(context -> {
                    throw failure;
                }))
                .expectErrorSatisfies(error -> assertSame(failure, error))
                .verify();

        assertEquals(List.of("begin", "rollback"), manager.events);
    }

    @Test
    void rollsBackOnAsynchronousCallbackFailure() {
        RecordingTransactionManager manager = new RecordingTransactionManager();
        SimpleReactiveTransactionOperations operations = new SimpleReactiveTransactionOperations(manager);
        IllegalStateException failure = new IllegalStateException("boom");

        StepVerifier.create(operations.inTransaction(context -> Mono.error(failure)))
                .expectErrorSatisfies(error -> assertSame(failure, error))
                .verify();

        assertEquals(List.of("begin", "rollback"), manager.events);
    }

    @Test
    void rollsBackOnCancellationAfterBegin() {
        RecordingTransactionManager manager = new RecordingTransactionManager();
        SimpleReactiveTransactionOperations operations = new SimpleReactiveTransactionOperations(manager);

        StepVerifier.create(operations.inTransaction(context -> Mono.never()))
                .then(() -> assertEquals(List.of("begin"), manager.events))
                .thenCancel()
                .verify();

        assertEquals(List.of("begin", "rollback"), manager.events);
    }

    @Test
    void propagatesBeginFailureWithoutCleanup() {
        RecordingTransactionManager manager = new RecordingTransactionManager();
        SimpleReactiveTransactionOperations operations = new SimpleReactiveTransactionOperations(manager);
        IllegalStateException failure = new IllegalStateException("begin failed");
        manager.beginFailure = failure;

        StepVerifier.create(operations.inTransaction(context -> Mono.just("ok")))
                .expectErrorSatisfies(error -> assertSame(failure, error))
                .verify();

        assertEquals(List.of("begin"), manager.events);
    }

    @Test
    void rollsBackWhenCommitFails() {
        RecordingTransactionManager manager = new RecordingTransactionManager();
        SimpleReactiveTransactionOperations operations = new SimpleReactiveTransactionOperations(manager);
        IllegalStateException failure = new IllegalStateException("commit failed");
        manager.commitFailure = failure;

        StepVerifier.create(operations.inTransaction(context -> Mono.just("ok")))
                .expectErrorSatisfies(error -> assertSame(failure, error))
                .verify();

        assertEquals(List.of("begin", "commit", "rollback"), manager.events);
    }

    @Test
    void surfacesRollbackFailureWhenCommitAndRollbackFail() {
        RecordingTransactionManager manager = new RecordingTransactionManager();
        SimpleReactiveTransactionOperations operations = new SimpleReactiveTransactionOperations(manager);
        IllegalStateException rollbackFailure = new IllegalStateException("rollback failed");
        manager.commitFailure = new IllegalStateException("commit failed");
        manager.rollbackFailure = rollbackFailure;

        StepVerifier.create(operations.inTransaction(context -> Mono.just("ok")))
                .expectErrorSatisfies(error -> assertSame(rollbackFailure, error))
                .verify();

        assertEquals(List.of("begin", "commit", "rollback"), manager.events);
    }

    @Test
    void surfacesRollbackFailureWhenRollbackAlsoFails() {
        RecordingTransactionManager manager = new RecordingTransactionManager();
        SimpleReactiveTransactionOperations operations = new SimpleReactiveTransactionOperations(manager);
        IllegalStateException callbackFailure = new IllegalStateException("callback failed");
        IllegalStateException rollbackFailure = new IllegalStateException("rollback failed");
        manager.rollbackFailure = rollbackFailure;

        StepVerifier.create(operations.inTransaction(context -> Mono.error(callbackFailure)))
                .expectErrorSatisfies(error -> assertSame(rollbackFailure, error))
                .verify();

        assertEquals(List.of("begin", "rollback"), manager.events);
    }

    private static final class RecordingTransactionManager implements ReactiveTransactionManager {
        private final List<String> events = new ArrayList<>();
        private RuntimeException beginFailure;
        private RuntimeException commitFailure;
        private RuntimeException rollbackFailure;

        @Override
        public Mono<TransactionContext> begin() {
            events.add("begin");
            if (beginFailure != null) {
                return Mono.error(beginFailure);
            }
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
