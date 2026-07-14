package io.nova.query.storedprocedure;

import io.nova.core.ReactiveEntityOperations;
import io.nova.core.RowAccessor;
import io.nova.metadata.DefaultNamingStrategy;
import io.nova.metadata.EntityMetadataFactory;
import io.nova.query.NativeQuery;
import io.nova.query.QuerySpec;
import io.nova.sql.BindMarkerStrategy;
import io.nova.sql.Dialect;
import io.nova.sql.SchemaGenerator;
import io.nova.sql.SqlRenderer;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.NamedStoredProcedureQuery;
import jakarta.persistence.ParameterMode;
import jakarta.persistence.StoredProcedureParameter;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link NamedStoredProcedureRegistry}/{@link ReactiveStoredProcedureQuery} 단위 테스트 —
 * {@code @NamedStoredProcedureQuery} 스캔·등록, 이름 충돌 거부, CALL 렌더링과 IN 바인딩 순서(named/positional),
 * 미지원 OUT/INOUT/REF_CURSOR fail-fast, 바인딩 누락 fail-fast, {@code @SqlResultSetMapping} 참조 안내.
 */
class NamedStoredProcedureRegistryTest {

    private final EntityMetadataFactory factory = new EntityMetadataFactory(new DefaultNamingStrategy());
    private final CapturingOperations operations = new CapturingOperations();
    private final NumberedDialect dialect = new NumberedDialect();

    private NamedStoredProcedureRegistry registry(Class<?>... entities) {
        return new NamedStoredProcedureRegistry(operations, dialect, factory, entities);
    }

    @Test
    void registersAndLooksUpDefinitions() {
        NamedStoredProcedureRegistry registry = registry(Person.class);
        assertTrue(registry.contains("Person.compute"));
        assertTrue(registry.contains("Person.byName"));
        assertFalse(registry.contains("Person.missing"));

        NamedStoredProcedureDefinition def = registry.definition("Person.byName");
        assertEquals("find_people", def.procedureName());
        assertEquals(List.of(Person.class), def.resultClasses());
        assertEquals(1, def.parameters().size());
        assertEquals("name", def.parameters().get(0).name());
        assertEquals(ParameterMode.IN, def.parameters().get(0).mode());
    }

    @Test
    void readsProcedureDeclaredOnMappedSuperclass() {
        NamedStoredProcedureRegistry registry = registry(Person.class);
        // Person.byName is declared on the @MappedSuperclass ancestor and must be visible on the subtype.
        assertTrue(registry.contains("Person.byName"));
    }

    @Test
    void unknownNameFailsFast() {
        NamedStoredProcedureRegistry registry = registry(Person.class);
        assertThrows(StoredProcedureException.class, () -> registry.definition("nope"));
        assertThrows(StoredProcedureException.class, () -> registry.createNamedStoredProcedureQuery("nope"));
    }

    @Test
    void duplicateNameAcrossEntitiesIsRejected() {
        StoredProcedureException ex =
                assertThrows(StoredProcedureException.class, () -> registry(Person.class, Clash.class));
        assertTrue(ex.getMessage().contains("Duplicate @NamedStoredProcedureQuery"));
    }

    @Test
    void rendersCallWithInParametersInDeclaredOrder() {
        NamedStoredProcedureRegistry registry = registry(Person.class);
        registry.createNamedStoredProcedureQuery("Person.compute")
                .setParameter("a", 10)
                .setParameter("b", 32)
                .getResultList()
                .collectList()
                .block();

        NativeQuery captured = operations.lastQuery.get();
        assertEquals("CALL compute_sum($1, $2)", captured.sql());
        assertEquals(List.of(10, 32), captured.bindings());
    }

    @Test
    void bindsInParametersByOneBasedPosition() {
        NamedStoredProcedureRegistry registry = registry(Person.class);
        registry.createNamedStoredProcedureQuery("Person.compute")
                .setParameter(1, 5)
                .setParameter(2, 6)
                .getResultList()
                .collectList()
                .block();

        NativeQuery captured = operations.lastQuery.get();
        assertEquals(List.of(5, 6), captured.bindings());
    }

    @Test
    void outParameterFailsFast() {
        NamedStoredProcedureRegistry registry = registry(ProcHolder.class);
        StoredProcedureException ex = assertThrows(StoredProcedureException.class,
                () -> registry.createNamedStoredProcedureQuery("Proc.withOut", row -> row)
                        .setParameter("in", 1)
                        .getResultList()
                        .collectList()
                        .block());
        assertTrue(ex.getMessage().contains("does not support output parameters"));
        assertTrue(ex.getMessage().contains("OUT"));
    }

    @Test
    void missingBindingFailsFast() {
        NamedStoredProcedureRegistry registry = registry(Person.class);
        assertThrows(StoredProcedureException.class,
                () -> registry.createNamedStoredProcedureQuery("Person.compute")
                        .setParameter("a", 1)
                        .getResultList()
                        .collectList()
                        .block());
    }

    @Test
    void noResultMappingReportsErrorOnGetResultList() {
        NamedStoredProcedureRegistry registry = registry(ProcHolder.class);
        StoredProcedureException ex = assertThrows(StoredProcedureException.class,
                () -> registry.createNamedStoredProcedureQuery("Proc.noResult")
                        .setParameter("in", 1)
                        .getResultList()
                        .collectList()
                        .block());
        assertTrue(ex.getMessage().contains("no result mapping"));
    }

    @Test
    void executeUpdateRunsProcedureWithoutMapping() {
        NamedStoredProcedureRegistry registry = registry(ProcHolder.class);
        Long affected = registry.createNamedStoredProcedureQuery("Proc.noResult")
                .setParameter("in", 7)
                .executeUpdate()
                .block();
        assertEquals(1L, affected);
        NativeQuery captured = operations.lastExecute.get();
        assertEquals("CALL do_thing($1)", captured.sql());
        assertEquals(List.of(7), captured.bindings());
    }

    @Test
    void resultSetMappingReferenceRequiresMapperOverload() {
        NamedStoredProcedureRegistry registry = registry(ProcHolder.class);
        StoredProcedureException ex = assertThrows(StoredProcedureException.class,
                () -> registry.createNamedStoredProcedureQuery("Proc.mapped"));
        assertTrue(ex.getMessage().contains("createNamedStoredProcedureQuery(name, mapper)"));
    }

    // ------------------------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------------------------

    @MappedSuperclass
    @NamedStoredProcedureQuery(name = "Person.byName", procedureName = "find_people",
            resultClasses = Person.class,
            parameters = @StoredProcedureParameter(name = "name", mode = ParameterMode.IN, type = String.class))
    static abstract class PersonBase {
        @Id
        @Column(name = "id")
        Long id;
        @Column(name = "name")
        String name;
    }

    @Entity
    @NamedStoredProcedureQuery(name = "Person.compute", procedureName = "compute_sum",
            resultClasses = Person.class,
            parameters = {
                    @StoredProcedureParameter(name = "a", mode = ParameterMode.IN, type = Integer.class),
                    @StoredProcedureParameter(name = "b", mode = ParameterMode.IN, type = Integer.class)})
    static class Person extends PersonBase {
    }

    @Entity
    @NamedStoredProcedureQuery(name = "Person.compute", procedureName = "other")
    static class Clash {
        @Id
        Long id;
    }

    @Entity
    @NamedStoredProcedureQuery(name = "Proc.withOut", procedureName = "with_out",
            parameters = {
                    @StoredProcedureParameter(name = "in", mode = ParameterMode.IN, type = Integer.class),
                    @StoredProcedureParameter(name = "out", mode = ParameterMode.OUT, type = Integer.class)})
    @NamedStoredProcedureQuery(name = "Proc.noResult", procedureName = "do_thing",
            parameters = @StoredProcedureParameter(name = "in", mode = ParameterMode.IN, type = Integer.class))
    @NamedStoredProcedureQuery(name = "Proc.mapped", procedureName = "mapped_proc",
            resultSetMappings = "someMapping")
    static class ProcHolder {
        @Id
        Long id;
    }

    // ------------------------------------------------------------------------------------
    // Test doubles
    // ------------------------------------------------------------------------------------

    private static final class NumberedDialect implements Dialect {
        private final BindMarkerStrategy bindMarkers = index -> "$" + (index + 1);

        @Override
        public String name() {
            return "numbered";
        }

        @Override
        public String quote(String identifier) {
            return "\"" + identifier + "\"";
        }

        @Override
        public BindMarkerStrategy bindMarkers() {
            return bindMarkers;
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

    private static final class CapturingOperations implements ReactiveEntityOperations {
        private final AtomicReference<NativeQuery> lastQuery = new AtomicReference<>();
        private final AtomicReference<NativeQuery> lastExecute = new AtomicReference<>();

        @Override
        public Mono<Long> executeNative(NativeQuery query) {
            lastExecute.set(query);
            return Mono.just(1L);
        }

        @Override
        public <T> Flux<T> queryNative(NativeQuery query, Function<RowAccessor, T> mapper) {
            lastQuery.set(query);
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
