package io.nova.core;

import io.nova.sql.SqlStatement;
import io.nova.tx.PhysicalTransactionScope;
import io.nova.tx.TransactionWriteObservation;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.util.context.Context;

import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhysicalTransactionWriteTrackingSqlExecutorTest {
    private static final SqlStatement STATEMENT = new SqlStatement("update widget set name = ?", List.of("name"));

    @Test
    void successfulWriteShapesMarkBothTransactionWriteObservers() {
        RecordingExecutor delegate = new RecordingExecutor();
        PhysicalTransactionWriteTrackingSqlExecutor executor = new PhysicalTransactionWriteTrackingSqlExecutor(delegate);

        assertMarksWrite(scope -> executor.execute(STATEMENT), () -> delegate.executeResult = Mono.just(1L));
        assertMarksWrite(scope -> executor.executeBatch("update widget set name = ?", List.of(List.of("name"))),
                () -> delegate.batchResult = Mono.just(1L));
        assertMarksWrite(scope -> executor.executeAndReturnGeneratedKey(STATEMENT, "id", Long.class),
                () -> delegate.generatedKeyResult = Mono.just(1L));
        assertMarksWrite(scope -> executor.executeBatchAndReturnGeneratedKeys(
                "insert into widget (name) values (?)", List.of(List.of("name")), "id", Long.class),
                () -> delegate.generatedKeysResult = Flux.just(1L));
    }

    @Test
    void queriesDoNotMarkTransactionWriteObservers() {
        RecordingExecutor delegate = new RecordingExecutor();
        PhysicalTransactionWriteTrackingSqlExecutor executor = new PhysicalTransactionWriteTrackingSqlExecutor(delegate);
        PhysicalTransactionScope scope = PhysicalTransactionScope.newOwner().scope();
        TransactionWriteObservation observation = new TransactionWriteObservation();

        StepVerifier.create(executor.queryOne(STATEMENT, row -> "one").contextWrite(context(scope, observation)))
                .expectNext("one")
                .verifyComplete();
        StepVerifier.create(executor.queryMany(STATEMENT, row -> "many").contextWrite(context(scope, observation)))
                .expectNext("many")
                .verifyComplete();

        assertFalse(scope.hasCompletedWrite());
        assertFalse(observation.hasCompletedWrite());
    }

    @Test
    void erroredWriteShapesDoNotMarkTransactionWriteObservers() {
        RecordingExecutor delegate = new RecordingExecutor();
        PhysicalTransactionWriteTrackingSqlExecutor executor = new PhysicalTransactionWriteTrackingSqlExecutor(delegate);

        assertDoesNotMarkOnError(scope -> executor.execute(STATEMENT),
                () -> delegate.executeResult = Mono.error(new IllegalStateException("boom")));
        assertDoesNotMarkOnError(scope -> executor.executeBatch("sql", List.of()),
                () -> delegate.batchResult = Mono.error(new IllegalStateException("boom")));
        assertDoesNotMarkOnError(scope -> executor.executeAndReturnGeneratedKey(STATEMENT, "id", Long.class),
                () -> delegate.generatedKeyResult = Mono.error(new IllegalStateException("boom")));
        assertDoesNotMarkOnError(scope -> executor.executeBatchAndReturnGeneratedKeys("sql", List.of(), "id", Long.class),
                () -> delegate.generatedKeysResult = Flux.error(new IllegalStateException("boom")));
    }

    @Test
    void cancelledWriteShapesDoNotMarkTransactionWriteObservers() {
        RecordingExecutor delegate = new RecordingExecutor();
        PhysicalTransactionWriteTrackingSqlExecutor executor = new PhysicalTransactionWriteTrackingSqlExecutor(delegate);

        assertDoesNotMarkOnCancel(scope -> executor.execute(STATEMENT), () -> delegate.executeResult = Mono.never());
        assertDoesNotMarkOnCancel(scope -> executor.executeBatch("sql", List.of()), () -> delegate.batchResult = Mono.never());
        assertDoesNotMarkOnCancel(scope -> executor.executeAndReturnGeneratedKey(STATEMENT, "id", Long.class),
                () -> delegate.generatedKeyResult = Mono.never());
        assertDoesNotMarkOnCancel(scope -> executor.executeBatchAndReturnGeneratedKeys("sql", List.of(), "id", Long.class),
                () -> delegate.generatedKeysResult = Flux.never());
    }

    private static void assertMarksWrite(Function<PhysicalTransactionScope, Publisher<?>> operation, Runnable setup) {
        setup.run();
        PhysicalTransactionScope scope = PhysicalTransactionScope.newOwner().scope();
        TransactionWriteObservation observation = new TransactionWriteObservation();

        StepVerifier.create(Flux.from(operation.apply(scope)).contextWrite(context(scope, observation)).then()).verifyComplete();

        assertTrue(scope.hasCompletedWrite());
        assertTrue(observation.hasCompletedWrite());
    }

    private static void assertDoesNotMarkOnError(
            Function<PhysicalTransactionScope, Publisher<?>> operation, Runnable setup) {
        setup.run();
        PhysicalTransactionScope scope = PhysicalTransactionScope.newOwner().scope();
        TransactionWriteObservation observation = new TransactionWriteObservation();

        StepVerifier.create(Flux.from(operation.apply(scope)).contextWrite(context(scope, observation)))
                .expectError(IllegalStateException.class)
                .verify();

        assertFalse(scope.hasCompletedWrite());
        assertFalse(observation.hasCompletedWrite());
    }

    private static void assertDoesNotMarkOnCancel(
            Function<PhysicalTransactionScope, Publisher<?>> operation, Runnable setup) {
        setup.run();
        PhysicalTransactionScope scope = PhysicalTransactionScope.newOwner().scope();
        TransactionWriteObservation observation = new TransactionWriteObservation();

        StepVerifier.create(Flux.from(operation.apply(scope)).contextWrite(context(scope, observation))).thenCancel().verify();

        assertFalse(scope.hasCompletedWrite());
        assertFalse(observation.hasCompletedWrite());
    }

    private static Context context(PhysicalTransactionScope scope, TransactionWriteObservation observation) {
        return Context.of(
                PhysicalTransactionScope.CONTEXT_KEY, scope,
                TransactionWriteObservation.CONTEXT_KEY, observation);
    }

    private static final class RecordingExecutor implements SqlExecutor {
        private Mono<Long> executeResult = Mono.just(1L);
        private Mono<Long> batchResult = Mono.just(1L);
        private Mono<Object> generatedKeyResult = Mono.just(1L);
        private Flux<Object> generatedKeysResult = Flux.just(1L);

        @Override
        public Mono<Long> execute(SqlStatement statement) {
            return executeResult;
        }

        @Override
        public <T> Mono<T> queryOne(SqlStatement statement, Function<RowAccessor, T> mapper) {
            return Mono.just(mapper.apply(null));
        }

        @Override
        public <T> Flux<T> queryMany(SqlStatement statement, Function<RowAccessor, T> mapper) {
            return Flux.just(mapper.apply(null));
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> Mono<T> executeAndReturnGeneratedKey(SqlStatement statement, String idColumn, Class<T> idType) {
            return (Mono<T>) generatedKeyResult;
        }

        @Override
        public Mono<Long> executeBatch(String sql, List<List<Object>> bindingsList) {
            return batchResult;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> Flux<T> executeBatchAndReturnGeneratedKeys(
                String sql, List<List<Object>> bindingsList, String idColumn, Class<T> idType) {
            return (Flux<T>) generatedKeysResult;
        }
    }
}
