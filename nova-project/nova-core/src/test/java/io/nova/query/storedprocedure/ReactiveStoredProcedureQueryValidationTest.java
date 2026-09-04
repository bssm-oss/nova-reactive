package io.nova.query.storedprocedure;

import io.nova.core.ReactiveEntityOperations;
import io.nova.core.RowAccessor;
import io.nova.query.NativeQuery;
import io.nova.query.QuerySpec;
import io.nova.sql.BindMarkerStrategy;
import io.nova.sql.Dialect;
import io.nova.sql.SchemaGenerator;
import io.nova.sql.SqlRenderer;
import jakarta.persistence.ParameterMode;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Validation must fail before {@link ReactiveEntityOperations} receives a native operation.
 */
class ReactiveStoredProcedureQueryValidationTest {

    @Test
    void settersRejectInvalidNamesPositionsAndTypesBeforeOperations() {
        CapturingOperations operations = new CapturingOperations();
        ReactiveStoredProcedureQuery<Object> query = query(operations, List.of(
                parameter("number", int.class),
                parameter(null, String.class)));

        assertRejectsWithoutOperations(operations, () -> query.setParameter(null, 1));
        assertRejectsWithoutOperations(operations, () -> query.setParameter(" ", 1));
        assertRejectsWithoutOperations(operations, () -> query.setParameter("unknown", 1));
        assertRejectsWithoutOperations(operations, () -> query.setParameter(0, 1));
        assertRejectsWithoutOperations(operations, () -> query.setParameter(-1, 1));
        assertRejectsWithoutOperations(operations, () -> query.setParameter(3, 1));
        assertRejectsWithoutOperations(operations, () -> query.setParameter("number", "1"));
        assertRejectsWithoutOperations(operations, () -> query.setParameter(2, 1));
    }

    @Test
    void acceptsPrimitiveWrappersNullAndNamedDeclarationsByPosition() {
        CapturingOperations operations = new CapturingOperations();
        ReactiveStoredProcedureQuery<Object> query = query(operations, List.of(
                parameter("number", int.class),
                parameter("text", String.class)));

        assertDoesNotThrow(() -> query.setParameter(1, Integer.valueOf(7)));
        assertDoesNotThrow(() -> query.setParameter(2, null));
        query.getResultList().collectList().block();

        assertEquals(1, operations.queryOperations.get());
        assertEquals(0, operations.executeOperations.get());
        assertEquals(7, operations.lastQuery.bindings().get(0));
        assertEquals(null, operations.lastQuery.bindings().get(1));
    }

    @Test
    void duplicateNamedDeclarationsCannotSilentlySelectOneBeforeOperations() {
        CapturingOperations operations = new CapturingOperations();
        assertRejectsWithoutOperations(operations, () -> query(operations, List.of(
                parameter("duplicate", Integer.class),
                parameter("duplicate", Integer.class))));
    }

    @Test
    void everyOutputModeFailsBeforeBindingsOrEitherNativeOperation() {
        for (ParameterMode mode : List.of(ParameterMode.OUT, ParameterMode.INOUT, ParameterMode.REF_CURSOR)) {
            CapturingOperations resultOperations = new CapturingOperations();
            ReactiveStoredProcedureQuery<Object> resultQuery = query(resultOperations, List.of(
                    parameter("input", Integer.class),
                    new StoredProcedureParameterDefinition("output", mode, Integer.class)));

            assertRejectsWithoutOperations(resultOperations,
                    () -> resultQuery.getResultList().collectList().block());

            CapturingOperations updateOperations = new CapturingOperations();
            ReactiveStoredProcedureQuery<Object> updateQuery = updateQuery(updateOperations, List.of(
                    parameter("input", Integer.class),
                    new StoredProcedureParameterDefinition("output", mode, Integer.class)));

            assertRejectsWithoutOperations(updateOperations, () -> updateQuery.executeUpdate().block());
        }
    }

    private static StoredProcedureParameterDefinition parameter(String name, Class<?> type) {
        return new StoredProcedureParameterDefinition(name, ParameterMode.IN, type);
    }

    private static ReactiveStoredProcedureQuery<Object> query(
            CapturingOperations operations, List<StoredProcedureParameterDefinition> parameters) {
        return new ReactiveStoredProcedureQuery<>("validate_proc", parameters, row -> row, operations, new TestDialect());
    }

    private static ReactiveStoredProcedureQuery<Object> updateQuery(
            CapturingOperations operations, List<StoredProcedureParameterDefinition> parameters) {
        return new ReactiveStoredProcedureQuery<>("validate_proc", parameters, null, operations, new TestDialect());
    }

    private static void assertRejectsWithoutOperations(CapturingOperations operations, Runnable action) {
        assertThrows(StoredProcedureException.class, action::run);
        assertEquals(0, operations.queryOperations.get());
        assertEquals(0, operations.executeOperations.get());
    }

    private static final class TestDialect implements Dialect {
        @Override
        public String name() {
            return "test";
        }

        @Override
        public String quote(String identifier) {
            return identifier;
        }

        @Override
        public BindMarkerStrategy bindMarkers() {
            return index -> "$" + index;
        }

        @Override
        public SqlRenderer sqlRenderer() {
            throw new UnsupportedOperationException();
        }

        @Override
        public SchemaGenerator schemaGenerator() {
            throw new UnsupportedOperationException();
        }
    }

    private static final class CapturingOperations implements ReactiveEntityOperations {
        private final AtomicInteger queryOperations = new AtomicInteger();
        private final AtomicInteger executeOperations = new AtomicInteger();
        private NativeQuery lastQuery;

        @Override
        public Mono<Long> executeNative(NativeQuery query) {
            executeOperations.incrementAndGet();
            return Mono.just(1L);
        }

        @Override
        public <T> Flux<T> queryNative(NativeQuery query, Function<RowAccessor, T> mapper) {
            queryOperations.incrementAndGet();
            lastQuery = query;
            return Flux.empty();
        }

        @Override
        public <T> Mono<T> save(T entity) {
            return Mono.just(entity);
        }

        @Override
        public <T, ID> Mono<T> findById(Class<T> entityType, ID id) {
            return Mono.empty();
        }

        @Override
        public <T> Flux<T> findAll(Class<T> entityType, QuerySpec querySpec) {
            return Flux.empty();
        }

        @Override
        public <T> Mono<Long> delete(T entity) {
            return Mono.just(0L);
        }

        @Override
        public <T, ID> Mono<Long> deleteById(Class<T> entityType, ID id) {
            return Mono.just(0L);
        }

        @Override
        public <T> Mono<Long> count(Class<T> entityType, QuerySpec querySpec) {
            return Mono.just(0L);
        }

        @Override
        public <T> Mono<Boolean> exists(Class<T> entityType, QuerySpec querySpec) {
            return Mono.just(false);
        }

        @Override
        public <R> Mono<R> inTransaction(Function<ReactiveEntityOperations, Mono<R>> callback) {
            return callback.apply(this);
        }
    }
}
