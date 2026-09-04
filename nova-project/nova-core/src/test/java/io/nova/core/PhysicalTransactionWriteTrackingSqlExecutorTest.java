package io.nova.core;

import io.nova.sql.SqlStatement;
import io.nova.tx.PhysicalTransactionScope;
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
    void successfulWriteShapesMarkThePhysicalTransactionScope() {
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
    void queriesDoNotMarkThePhysicalTransactionScope() {
        RecordingExecutor delegate = new RecordingExecutor();
        PhysicalTransactionWriteTrackingSqlExecutor executor = new PhysicalTransactionWriteTrackingSqlExecutor(delegate);
        PhysicalTransactionScope scope = PhysicalTransactionScope.newOwner().scope();

        StepVerifier.create(executor.queryOne(STATEMENT, row -> "one").contextWrite(context(scope)))
                .expectNext("one")
                .verifyComplete();
        StepVerifier.create(executor.queryMany(STATEMENT, row -> "many").contextWrite(context(scope)))
                .expectNext("many")
                .verifyComplete();

        assertFalse(scope.hasCompletedWrite());
    }

    @Test
    void erroredWriteShapesDoNotMarkThePhysicalTransactionScope() {
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
    void cancelledWriteShapesDoNotMarkThePhysicalTransactionScope() {
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

        StepVerifier.create(Flux.from(operation.apply(scope)).contextWrite(context(scope))).verifyComplete();

        assertTrue(scope.hasCompletedWrite());
    }

    private static void assertDoesNotMarkOnError(
            Function<PhysicalTransactionScope, Publisher<?>> operation, Runnable setup) {
        setup.run();
        PhysicalTransactionScope scope = PhysicalTransactionScope.newOwner().scope();

        StepVerifier.create(Flux.from(operation.apply(scope)).contextWrite(context(scope)))
                .expectError(IllegalStateException.class)
                .verify();

        assertFalse(scope.hasCompletedWrite());
    }

    private static void assertDoesNotMarkOnCancel(
            Function<PhysicalTransactionScope, Publisher<?>> operation, Runnable setup) {
        setup.run();
        PhysicalTransactionScope scope = PhysicalTransactionScope.newOwner().scope();

        StepVerifier.create(Flux.from(operation.apply(scope)).contextWrite(context(scope))).thenCancel().verify();

        assertFalse(scope.hasCompletedWrite());
    }

    private static Context context(PhysicalTransactionScope scope) {
        return Context.of(PhysicalTransactionScope.CONTEXT_KEY, scope);
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
