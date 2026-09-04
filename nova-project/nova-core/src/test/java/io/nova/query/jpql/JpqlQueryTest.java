package io.nova.query.jpql;

import io.nova.core.ReactiveEntityOperations;
import io.nova.core.RowAccessor;
import io.nova.query.Condition;
import io.nova.query.NativeQuery;
import io.nova.query.QuerySpec;
import io.nova.metadata.DefaultNamingStrategy;
import io.nova.metadata.EntityMetadataFactory;
import io.nova.query.jpql.ast.JpqlStatement;
import io.nova.sql.BindMarkerStrategy;
import io.nova.sql.Dialect;
import io.nova.sql.SchemaGenerator;
import io.nova.sql.SqlRenderer;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JpqlQueryTest {

    private final JpqlEntityResolver resolver = new JpqlEntityResolver(
            new EntityMetadataFactory(new DefaultNamingStrategy()), List.of(Employee.class, Department.class));
    private final JpqlSqlBuilder builder = new JpqlSqlBuilder(new TestDialect(), resolver);

    @Test
    void pagesScalarAggregateAndConstructorProjectionsWithoutChangingSqlBindings() {
        for (String jpql : List.of(
                "SELECT e.name FROM Employee e WHERE e.name = :name",
                "SELECT COUNT(e) FROM Employee e WHERE e.name = :name",
                "SELECT NEW " + EmployeeDto.class.getName() + "(e.name) FROM Employee e WHERE e.name = :name")) {
            RecordingOperations recorder = new RecordingOperations();
            JpqlQuery<Object> query = query(jpql, recorder.operations())
                    .setParameter("name", "'; drop table employee; --")
                    .setFirstResult(1)
                    .setMaxResults(2);

            StepVerifier.create(query.getResultList())
                    .expectNextCount(2)
                    .verifyComplete();
            assertFalse(recorder.nativeQuery().sql().contains("drop table"));
            assertEquals(List.of("'; drop table employee; --"), recorder.nativeQuery().bindings());
        }
    }

    @Test
    void maxResultsZeroCompletesWithoutExecutingAnyResultShape() {
        for (String jpql : List.of(
                "SELECT e FROM Employee e",
                "SELECT e.name FROM Employee e",
                "SELECT COUNT(e) FROM Employee e",
                "SELECT NEW " + EmployeeDto.class.getName() + "(e.name) FROM Employee e")) {
            RecordingOperations recorder = new RecordingOperations();

            StepVerifier.create(query(jpql, recorder.operations()).setMaxResults(0).getResultList())
                    .verifyComplete();
            assertEquals(0, recorder.invocations());
        }
    }

    @Test
    void offsetOnlyEntityUsesMaximumPageSize() {
        RecordingOperations recorder = new RecordingOperations();

        StepVerifier.create(query("SELECT e FROM Employee e", recorder.operations()).setFirstResult(7).getResultList())
                .verifyComplete();

        assertEquals(Integer.MAX_VALUE, recorder.querySpec().pageable().limit());
        assertEquals(7L, recorder.querySpec().pageable().offset());
    }

    @Test
    void windowsFilteringJoinIdsBeforeHydration() {
        RecordingOperations recorder = new RecordingOperations(List.of(1L, 2L, 3L));

        StepVerifier.create(query("SELECT e FROM Employee e JOIN e.department d ORDER BY e.id",
                        recorder.operations())
                .setFirstResult(1)
                .setMaxResults(1)
                .getResultList()).verifyComplete();

        Condition ids = (Condition) recorder.querySpec().predicate();
        assertEquals(List.of(2L), ids.value());
    }

    @Test
    void offsetOnlyJoinHydratesDistinctIdsInIncrementalBoundedChunks() {
        List<Object> ids = new ArrayList<>();
        for (long id = 1; id <= 514; id++) {
            ids.add(id);
        }
        RecordingOperations recorder = new RecordingOperations(ids);

        StepVerifier.create(query("SELECT e FROM Employee e JOIN e.department d ORDER BY e.id",
                        recorder.operations())
                .setFirstResult(1)
                .getResultList()).verifyComplete();

        assertEquals(3, recorder.querySpecs().size());
        assertEquals(256, ((List<?>) ((Condition) recorder.querySpecs().get(0).predicate()).value()).size());
        assertEquals(256, ((List<?>) ((Condition) recorder.querySpecs().get(1).predicate()).value()).size());
        assertEquals(List.of(514L), ((Condition) recorder.querySpecs().get(2).predicate()).value());
    }

    @Test
    void includesNonIdOrderExpressionInDistinctJoinIdSql() {
        RecordingOperations recorder = new RecordingOperations(List.of(1L));

        StepVerifier.create(query("SELECT e FROM Employee e JOIN e.department d ORDER BY e.name",
                        recorder.operations())
                .setMaxResults(1)
                .getResultList()).verifyComplete();

        assertEquals("select distinct e.\"id\" as \"c0\", e.\"name\" as \"c1\" from \"employee\" e "
                        + "join \"department\" d on e.\"department_id\" = d.\"id\" order by e.\"name\" asc",
                recorder.nativeQuery().sql());
    }

    @Test
    void skipsInvalidDtoRowsBeforeConstructorCoercion() {
        RecordingOperations recorder = new RecordingOperations(java.util.Arrays.asList(null, 7));
        JpqlQuery<Object> query = query(
                "SELECT NEW " + IntDto.class.getName() + "(e.id) FROM Employee e", recorder.operations())
                .setFirstResult(1)
                .setMaxResults(1);

        StepVerifier.create(query.getResultList())
                // The selected row's Integer storage value is coerced to Employee.id's Long before DTO coercion.
                .assertNext(result -> assertEquals(7, ((IntDto) result).id()))
                .verifyComplete();
    }

    @Test
    void declaredScalarResultTypeRejectsIncompatibleValue() {
        RecordingOperations recorder = new RecordingOperations(List.of("Ada"));

        StepVerifier.create(typedQuery("SELECT e.name FROM Employee e", Integer.class, recorder.operations())
                        .getResultList())
                .expectError(JpqlException.class)
                .verify();
    }

    @Test
    void declaredScalarResultTypeUsesExistingPropertyNumericCoercion() {
        RecordingOperations recorder = new RecordingOperations(List.of(7));

        StepVerifier.create(typedQuery("SELECT e.id FROM Employee e", Long.class, recorder.operations())
                        .getResultList())
                .expectNext(7L)
                .verifyComplete();
    }

    @Test
    void primitiveDeclaredScalarResultEmitsBoxedValue() {
        RecordingOperations recorder = new RecordingOperations(List.of(7));

        StepVerifier.create(typedQuery("SELECT e.id FROM Employee e", primitiveType(long.class), recorder.operations())
                        .getResultList())
                .expectNext(7L)
                .verifyComplete();
    }

    @Test
    void primitiveDeclaredScalarResultRejectsNull() {
        RecordingOperations recorder = new RecordingOperations(java.util.Arrays.asList((Object) null));

        StepVerifier.create(typedQuery("SELECT e.name FROM Employee e", primitiveType(long.class), recorder.operations())
                        .getResultList())
                .expectError(JpqlException.class)
                .verify();
    }

    @Test
    void declaredMultiSelectResultTypeRequiresObjectArray() {
        RecordingOperations recorder = new RecordingOperations(List.of("Ada"));

        StepVerifier.create(typedQuery("SELECT e.name, e.id FROM Employee e", String.class, recorder.operations())
                        .getResultList())
                .expectError(JpqlException.class)
                .verify();
    }

    @Test
    void objectAndObjectArrayRetainProjectionShapeAutoDetection() {
        RecordingOperations scalarRecorder = new RecordingOperations(List.of("Ada"));
        RecordingOperations multiRecorder = new RecordingOperations(List.of("Ada"));

        StepVerifier.create(typedQuery("SELECT e.name FROM Employee e", Object.class, scalarRecorder.operations())
                        .getResultList())
                .expectNext("Ada")
                .verifyComplete();
        StepVerifier.create(typedQuery("SELECT e.name, e.name FROM Employee e", Object[].class, multiRecorder.operations())
                        .getResultList())
                .assertNext(values -> assertEquals(2, values.length))
                .verifyComplete();
    }

    @Test
    void declaredSelectNewResultTypeMustMatchProjectionClass() {
        RecordingOperations recorder = new RecordingOperations(List.of("Ada"));

        StepVerifier.create(typedQuery("SELECT NEW " + EmployeeDto.class.getName() + "(e.name) FROM Employee e",
                        String.class, recorder.operations()).getResultList())
                .expectError(JpqlException.class)
                .verify();
    }

    @Test
    void incompatibleProjectionTypesSignalJpqlExceptionsFromTheirPublishers() {
        JpqlQuery<Integer> scalar = typedQuery(
                "SELECT e.name FROM Employee e", Integer.class, new RecordingOperations(List.of("Ada")).operations());
        JpqlQuery<String> multiSelect = typedQuery(
                "SELECT e.name, e.name FROM Employee e", String.class,
                new RecordingOperations(List.of("Ada")).operations());
        JpqlQuery<String> selectNew = typedQuery(
                "SELECT NEW " + EmployeeDto.class.getName() + "(e.name) FROM Employee e", String.class,
                new RecordingOperations(List.of("Ada")).operations());

        StepVerifier.create(scalar.getResultList())
                .expectErrorSatisfies(error -> assertEquals(JpqlException.class, error.getClass()))
                .verify();
        StepVerifier.create(multiSelect.getResultList())
                .expectErrorSatisfies(error -> assertEquals(JpqlException.class, error.getClass()))
                .verify();
        StepVerifier.create(selectNew.getResultList())
                .expectErrorSatisfies(error -> assertEquals(JpqlException.class, error.getClass()))
                .verify();
    }

    @Test
    void objectResultTypesAndPrimitivePropertyNumericConversionRemainValid() {
        RecordingOperations scalarRecorder = new RecordingOperations(List.of(7));
        RecordingOperations multiRecorder = new RecordingOperations(List.of("Ada"));
        RecordingOperations boxedPrimitiveRecorder = new RecordingOperations(List.of(7L));

        StepVerifier.create(typedQuery("SELECT e.id FROM Employee e", Object.class, scalarRecorder.operations())
                        .getResultList())
                .expectNext(7L)
                .verifyComplete();
        StepVerifier.create(typedQuery("SELECT e.name, e.name FROM Employee e", Object[].class,
                        multiRecorder.operations()).getResultList())
                .assertNext(values -> assertArrayEquals(new Object[] {"Ada", "Ada"}, values))
                .verifyComplete();
        StepVerifier.create(typedQuery("SELECT e.score FROM Employee e", Integer.class,
                        boxedPrimitiveRecorder.operations()).getResultList())
                .expectNext(7)
                .verifyComplete();
    }

    @Test
    void integerMaximumResultLimitDoesNotOverflowProjectionPaging() {
        RecordingOperations recorder = new RecordingOperations();

        StepVerifier.create(query("SELECT e.name FROM Employee e", recorder.operations())
                .setFirstResult(1)
                .setMaxResults(Integer.MAX_VALUE)
                .getResultList()).expectNextCount(2).verifyComplete();
    }

    @Test
    void paginationRejectsNegativeValuesBeforeSqlConstruction() {
        JpqlQuery<Object> query = query("SELECT e.name FROM Employee e", new RecordingOperations().operations());

        assertThrows(JpqlException.class, () -> query.setFirstResult(-1));
        assertThrows(JpqlException.class, () -> query.setMaxResults(-1));
    }

    private JpqlQuery<Object> query(String jpql, ReactiveEntityOperations operations) {
        return typedQuery(jpql, Object.class, operations);
    }

    private <T> JpqlQuery<T> typedQuery(String jpql, Class<T> resultType, ReactiveEntityOperations operations) {
        return new JpqlQuery<>(
                (JpqlStatement.Select) new JpqlParser(jpql).parse(),
                resultType,
                operations,
                builder,
                new JpqlEntityQueryPlanner(resolver));
    }

    @SuppressWarnings("unchecked")
    private static <T> Class<T> primitiveType(Class<?> primitive) {
        return (Class<T>) primitive;
    }

    private static final class RecordingOperations {
        private final AtomicInteger invocations = new AtomicInteger();
        private final AtomicReference<NativeQuery> nativeQuery = new AtomicReference<>();
        private final AtomicReference<QuerySpec> querySpec = new AtomicReference<>();
        private final List<QuerySpec> querySpecs = new ArrayList<>();
        private final List<Object> nativeRows;

        private RecordingOperations() {
            this(List.of("first", "second", "third"));
        }

        private RecordingOperations(List<Object> nativeRows) {
            this.nativeRows = nativeRows;
        }

        private ReactiveEntityOperations operations() {
            return (ReactiveEntityOperations) Proxy.newProxyInstance(
                    getClass().getClassLoader(),
                    new Class<?>[] {ReactiveEntityOperations.class},
                    (proxy, method, arguments) -> {
                        invocations.incrementAndGet();
                        if (method.getName().equals("queryNative")) {
                            nativeQuery.set((NativeQuery) arguments[0]);
                            @SuppressWarnings("unchecked")
                            java.util.function.Function<RowAccessor, Object> mapper =
                                    (java.util.function.Function<RowAccessor, Object>) arguments[1];
                            return Flux.range(0, nativeRows.size()).map(index -> mapper.apply(new RowAccessor() {
                                @Override
                                public <T> T get(String columnName, Class<T> type) {
                                    return type.cast(nativeRows.get(index));
                                }
                            }));
                        }
                        if (method.getName().equals("findAll")) {
                            querySpec.set((QuerySpec) arguments[1]);
                            querySpecs.add((QuerySpec) arguments[1]);
                            return Flux.empty();
                        }
                        throw new AssertionError("Unexpected operation: " + method.getName());
                    });
        }

        private int invocations() {
            return invocations.get();
        }

        private NativeQuery nativeQuery() {
            return nativeQuery.get();
        }

        private QuerySpec querySpec() {
            return querySpec.get();
        }

        private List<QuerySpec> querySpecs() {
            return querySpecs;
        }
    }

    @Entity
    @Table(name = "employee")
    public static class Employee {
        @Id
        @Column(name = "id")
        private Long id;

        @Column(name = "name")
        private String name;

        @Column(name = "score")
        private int score;

        @ManyToOne
        @JoinColumn(name = "department_id")
        private Department department;
    }

    @Entity
    @Table(name = "department")
    public static class Department {
        @Id
        @Column(name = "id")
        private Long id;
    }

    public static class EmployeeDto {
        public EmployeeDto(String name) {
        }
    }

    public static class IntDto {
        private final int id;

        public IntDto(int id) {
            this.id = id;
        }

        public int id() {
            return id;
        }
    }

    private static final class TestDialect implements Dialect {
        @Override
        public String name() {
            return "test";
        }

        @Override
        public String quote(String identifier) {
            return "\"" + identifier + "\"";
        }

        @Override
        public BindMarkerStrategy bindMarkers() {
            return index -> "?";
        }

        @Override
        public SqlRenderer sqlRenderer() {
            throw new UnsupportedOperationException("not needed");
        }

        @Override
        public SchemaGenerator schemaGenerator() {
            throw new UnsupportedOperationException("not needed");
        }
    }
}
