package io.nova.query.jpql;

import io.nova.core.ReactiveEntityOperations;
import io.nova.core.RowAccessor;
import io.nova.metadata.DefaultNamingStrategy;
import io.nova.metadata.EntityMetadataFactory;
import io.nova.metadata.NamedQueryDefinition;
import io.nova.query.NativeQuery;
import io.nova.query.QuerySpec;
import io.nova.sql.BindMarkerStrategy;
import io.nova.sql.Dialect;
import io.nova.sql.SchemaGenerator;
import io.nova.sql.SqlRenderer;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.NamedNativeQuery;
import jakarta.persistence.NamedQuery;
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
 * {@link NamedQueryRegistry} 단위 테스트 — 등록·조회, 이름 충돌 거부, JPQL/네이티브 오용 fail-fast,
 * 네이티브 JPA 파라미터 마커({@code :name}/{@code ?n})의 dialect 마커 치환·바인딩 순서.
 */
class NamedQueryRegistryTest {

    private final EntityMetadataFactory factory = new EntityMetadataFactory(new DefaultNamingStrategy());
    private final CapturingOperations operations = new CapturingOperations();
    private final NumberedDialect dialect = new NumberedDialect();

    private NamedQueryRegistry registry(Class<?>... entities) {
        return new NamedQueryRegistry(operations, dialect, factory, entities);
    }

    @Test
    void registersAndLooksUpDefinitions() {
        NamedQueryRegistry registry = registry(Person.class);
        assertTrue(registry.contains("Person.byName"));
        assertTrue(registry.contains("Person.rawById"));
        assertFalse(registry.contains("Person.missing"));

        NamedQueryDefinition jpql = registry.definition("Person.byName");
        assertFalse(jpql.nativeQuery());
        NamedQueryDefinition nativeDef = registry.definition("Person.rawById");
        assertTrue(nativeDef.nativeQuery());
        assertEquals(Person.class, nativeDef.resultClass());
    }

    @Test
    void unknownNameFailsFast() {
        NamedQueryRegistry registry = registry(Person.class);
        assertThrows(NamedQueryException.class, () -> registry.definition("nope"));
        assertThrows(NamedQueryException.class, () -> registry.createQuery("nope"));
        assertThrows(NamedQueryException.class, () -> registry.createNativeQuery("nope"));
    }

    @Test
    void duplicateNameAcrossEntitiesIsRejected() {
        NamedQueryException ex =
                assertThrows(NamedQueryException.class, () -> registry(Person.class, Clash.class));
        assertTrue(ex.getMessage().contains("Duplicate named query"));
    }

    @Test
    void createQueryRejectsNativeName() {
        NamedQueryRegistry registry = registry(Person.class);
        NamedQueryException ex =
                assertThrows(NamedQueryException.class, () -> registry.createQuery("Person.rawById"));
        assertTrue(ex.getMessage().contains("@NamedNativeQuery"));
    }

    @Test
    void createNativeQueryRejectsJpqlName() {
        NamedQueryRegistry registry = registry(Person.class);
        NamedQueryException ex =
                assertThrows(NamedQueryException.class, () -> registry.createNativeQuery("Person.byName"));
        assertTrue(ex.getMessage().contains("@NamedQuery"));
    }

    @Test
    void translatesNamedParametersToDialectMarkersInOrder() {
        NamedQueryRegistry registry = registry(Person.class);
        registry.createNativeQuery("Person.rawByNameAndAge", row -> row)
                .setParameter("name", "Ada")
                .setParameter("age", 40)
                .executeUpdate()
                .block();

        NativeQuery captured = operations.lastExecute.get();
        assertEquals("UPDATE person SET age = $1 WHERE name = $2", captured.sql());
        assertEquals(List.of(40, "Ada"), captured.bindings());
    }

    @Test
    void translatesPositionalParametersAndPreservesCastsAndLiterals() {
        NamedQueryRegistry registry = registry(Person.class);
        registry.createNativeQuery("Person.rawPositional", row -> row)
                .setParameter(1, "x")
                .setParameter(2, 7)
                .executeUpdate()
                .block();

        NativeQuery captured = operations.lastExecute.get();
        // '::' 캐스트와 문자열 리터럴 안의 ':' 는 파라미터로 오인하지 않는다.
        assertEquals("UPDATE person SET tag = $1::text WHERE note = 'a:b' AND age = $2", captured.sql());
        assertEquals(List.of("x", 7), captured.bindings());
    }

    @Test
    void missingBindingFailsFast() {
        NamedQueryRegistry registry = registry(Person.class);
        assertThrows(NamedQueryException.class, () -> registry.createNativeQuery("Person.rawByNameAndAge", row -> row)
                .setParameter("name", "Ada")
                .executeUpdate()
                .block());
    }

    // ------------------------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------------------------

    @Entity
    @NamedQuery(name = "Person.byName", query = "SELECT p FROM Person p WHERE p.name = :name")
    @NamedNativeQuery(name = "Person.rawById", query = "SELECT * FROM person WHERE id = ?1", resultClass = Person.class)
    @NamedNativeQuery(name = "Person.rawByNameAndAge", query = "UPDATE person SET age = :age WHERE name = :name")
    @NamedNativeQuery(name = "Person.rawPositional",
            query = "UPDATE person SET tag = ?1::text WHERE note = 'a:b' AND age = ?2")
    static class Person {
        @Id
        Long id;
        String name;
        int age;
        String tag;
        String note;
    }

    @Entity
    @NamedQuery(name = "Person.byName", query = "SELECT c FROM Clash c")
    static class Clash {
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
        private final AtomicReference<NativeQuery> lastExecute = new AtomicReference<>();

        @Override
        public Mono<Long> executeNative(NativeQuery query) {
            lastExecute.set(query);
            return Mono.just(1L);
        }

        @Override
        public <T> Flux<T> queryNative(NativeQuery query, Function<RowAccessor, T> mapper) {
            lastExecute.set(query);
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
