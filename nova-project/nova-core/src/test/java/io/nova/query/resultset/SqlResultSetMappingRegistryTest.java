package io.nova.query.resultset;

import io.nova.core.ReactiveEntityOperations;
import io.nova.core.RowAccessor;
import io.nova.metadata.DefaultNamingStrategy;
import io.nova.metadata.EntityMetadataFactory;
import io.nova.query.NativeQuery;
import io.nova.query.QuerySpec;
import io.nova.query.jpql.NamedQueryRegistry;
import io.nova.sql.BindMarkerStrategy;
import io.nova.sql.Dialect;
import io.nova.sql.SchemaGenerator;
import io.nova.sql.SqlRenderer;
import jakarta.persistence.ColumnResult;
import jakarta.persistence.ConstructorResult;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityResult;
import jakarta.persistence.FieldResult;
import jakarta.persistence.Id;
import jakarta.persistence.NamedNativeQuery;
import jakarta.persistence.SqlResultSetMapping;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SqlResultSetMappingRegistry} 단위 테스트 — 등록·이름 충돌 fail-fast, 미등록 조회 fail-fast,
 * {@code @ConstructorResult}/{@code @ColumnResult} 매핑 출력, {@code @FieldResult}/생성자 인자 불일치 fail-fast,
 * {@code @NamedNativeQuery(resultSetMapping=)} 연결.
 */
class SqlResultSetMappingRegistryTest {

    private final EntityMetadataFactory factory = new EntityMetadataFactory(new DefaultNamingStrategy());
    private final MapperOperations operations = new MapperOperations();

    private SqlResultSetMappingRegistry registry(Class<?>... entities) {
        return new SqlResultSetMappingRegistry(operations, factory, entities);
    }

    @Test
    void registersAndLooksUp() {
        SqlResultSetMappingRegistry registry = registry(WidgetMappings.class);
        assertTrue(registry.contains("ctor"));
        assertTrue(registry.contains("scalar"));
        assertTrue(registry.mappingNames().contains("ctor"));
        IllegalArgumentException ex =
                assertThrows(IllegalArgumentException.class, () -> registry.definition("missing"));
        assertTrue(ex.getMessage().contains("No @SqlResultSetMapping registered"));
    }

    @Test
    void duplicateNameAcrossEntitiesIsRejected() {
        IllegalStateException ex =
                assertThrows(IllegalStateException.class, () -> registry(WidgetMappings.class, ClashMapping.class));
        assertTrue(ex.getMessage().contains("Duplicate @SqlResultSetMapping"));
    }

    @Test
    void constructorResultMapsRow() {
        SqlResultSetMappingRegistry registry = registry(WidgetMappings.class);
        operations.row = row(Map.of("w_name", "Alpha", "w_price", 10));
        StepVerifier.create(registry.queryNative("SELECT name AS w_name, price AS w_price FROM widget", "ctor"))
                .assertNext(result -> {
                    WidgetView view = (WidgetView) result;
                    assertEquals("Alpha", view.name);
                    assertEquals(0, view.price.compareTo(new BigDecimal("10")));
                })
                .verifyComplete();
    }

    @Test
    void columnResultMapsScalar() {
        SqlResultSetMappingRegistry registry = registry(WidgetMappings.class);
        operations.row = row(Map.of("total", 42L));
        StepVerifier.create(registry.queryNative("SELECT count(*) AS total FROM widget", "scalar"))
                .assertNext(result -> assertEquals(42L, result))
                .verifyComplete();
    }

    @Test
    void mixedResultsAssembleObjectArray() {
        SqlResultSetMappingRegistry registry = registry(WidgetMappings.class);
        Map<String, Object> values = new HashMap<>();
        values.put("w_name", "Beta");
        values.put("w_price", 20);
        values.put("extra", 7L);
        operations.row = row(values);
        StepVerifier.create(registry.queryNative("SELECT ...", "mixed"))
                .assertNext(result -> {
                    Object[] tuple = (Object[]) result;
                    assertEquals(2, tuple.length);
                    assertEquals("Beta", ((WidgetView) tuple[0]).name);
                    assertEquals(7L, tuple[1]);
                })
                .verifyComplete();
    }

    @Test
    void columnResultCoercesWhenDriverReturnsDifferentType() {
        SqlResultSetMappingRegistry registry = registry(WidgetMappings.class);
        // scalar 매핑은 type=Long을 선언하지만, driver가 Integer를 반환하는 상황을 모사한다.
        operations.row = row(Map.of("total", Integer.valueOf(42)));
        StepVerifier.create(registry.queryNative("SELECT count(*) AS total FROM widget", "scalar"))
                .assertNext(result -> {
                    assertTrue(result instanceof Long, "declared Long must be coerced from driver Integer");
                    assertEquals(42L, result);
                })
                .verifyComplete();
    }

    @Test
    void unknownMappingFailsFast() {
        SqlResultSetMappingRegistry registry = registry(WidgetMappings.class);
        assertThrows(IllegalArgumentException.class, () -> registry.queryNative("SELECT 1", "nope"));
    }

    @Test
    void unknownFieldResultAttributeFailsFast() {
        SqlResultSetMappingRegistry registry = registry(BadFieldMapping.class);
        IllegalStateException ex =
                assertThrows(IllegalStateException.class, () -> registry.queryNative("SELECT 1", "badField"));
        assertTrue(ex.getMessage().contains("nonexistent"));
    }

    @Test
    void constructorArityMismatchFailsFast() {
        SqlResultSetMappingRegistry registry = registry(BadCtorMapping.class);
        IllegalStateException ex =
                assertThrows(IllegalStateException.class, () -> registry.queryNative("SELECT 1", "badCtor"));
        assertTrue(ex.getMessage().contains("no constructor accepting"));
    }

    @Test
    void multipleEntityResultsUnsupported() {
        SqlResultSetMappingRegistry registry = registry(MultiEntityMapping.class);
        IllegalStateException ex =
                assertThrows(IllegalStateException.class, () -> registry.queryNative("SELECT 1", "multi"));
        assertTrue(ex.getMessage().contains("multi-entity"));
    }

    @Test
    void entityResultOverCompositeToOneFailsFast() {
        // @EntityResult 매퍼도 property당 단일 컬럼만 읽어 복합 to-one을 대표 컬럼 하나로만 resolve → fail-fast.
        SqlResultSetMappingRegistry registry =
                registry(io.nova.support.fixtures.FixtureEntities.CompositeJoinChild.class);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> registry.queryNative("SELECT 1", "CompositeJoinChild.map"));
        assertTrue(ex.getMessage().contains("composite-key"));
    }

    @Test
    void namedNativeQueryWithoutResultSetMappingFailsFast() {
        SqlResultSetMappingRegistry registry = registry(WidgetMappings.class);
        NamedQueryRegistry named =
                new NamedQueryRegistry(operations, new NumberedDialect(), factory, NamedNativeFixture.class);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> registry.createNativeQuery(named, "Fixture.noMapping"));
        assertTrue(ex.getMessage().contains("does not declare a resultSetMapping"));
    }

    // ------------------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------------------

    private static RowAccessor row(Map<String, Object> values) {
        return new MapRow(new HashMap<>(values));
    }

    private record MapRow(Map<String, Object> values) implements RowAccessor {
        @Override
        @SuppressWarnings("unchecked")
        public <T> T get(String columnName, Class<T> type) {
            if (!values.containsKey(columnName)) {
                throw new IllegalArgumentException("no column '" + columnName + "'");
            }
            return (T) values.get(columnName);
        }
    }

    // ------------------------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------------------------

    @Entity
    static class Widget {
        @Id
        Long id;
        String name;
        BigDecimal price;
    }

    static class WidgetView {
        final String name;
        final BigDecimal price;

        WidgetView(String name, BigDecimal price) {
            this.name = name;
            this.price = price;
        }
    }

    @Entity
    @SqlResultSetMapping(name = "ctor",
            classes = @ConstructorResult(targetClass = WidgetView.class,
                    columns = {@ColumnResult(name = "w_name"),
                            @ColumnResult(name = "w_price", type = BigDecimal.class)}))
    @SqlResultSetMapping(name = "scalar", columns = @ColumnResult(name = "total", type = Long.class))
    @SqlResultSetMapping(name = "mixed",
            classes = @ConstructorResult(targetClass = WidgetView.class,
                    columns = {@ColumnResult(name = "w_name"),
                            @ColumnResult(name = "w_price", type = BigDecimal.class)}),
            columns = @ColumnResult(name = "extra", type = Long.class))
    static class WidgetMappings {
        @Id
        Long id;
    }

    @Entity
    @SqlResultSetMapping(name = "ctor", columns = @ColumnResult(name = "other"))
    static class ClashMapping {
        @Id
        Long id;
    }

    @Entity
    @SqlResultSetMapping(name = "badField",
            entities = @EntityResult(entityClass = Widget.class,
                    fields = @FieldResult(name = "nonexistent", column = "c")))
    static class BadFieldMapping {
        @Id
        Long id;
    }

    @Entity
    @SqlResultSetMapping(name = "badCtor",
            classes = @ConstructorResult(targetClass = WidgetView.class,
                    columns = {@ColumnResult(name = "a"), @ColumnResult(name = "b"), @ColumnResult(name = "c")}))
    static class BadCtorMapping {
        @Id
        Long id;
    }

    @Entity
    @SqlResultSetMapping(name = "multi",
            entities = {@EntityResult(entityClass = Widget.class), @EntityResult(entityClass = Widget.class)})
    static class MultiEntityMapping {
        @Id
        Long id;
    }

    @Entity
    @NamedNativeQuery(name = "Fixture.noMapping",
            query = "SELECT * FROM widget", resultClass = Widget.class)
    static class NamedNativeFixture {
        @Id
        Long id;
        String name;
        BigDecimal price;
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

    private static final class MapperOperations implements ReactiveEntityOperations {
        private RowAccessor row;

        @Override
        public <T> Flux<T> queryNative(NativeQuery query, Function<RowAccessor, T> mapper) {
            if (row == null) {
                return Flux.empty();
            }
            return Flux.just(mapper.apply(row));
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
