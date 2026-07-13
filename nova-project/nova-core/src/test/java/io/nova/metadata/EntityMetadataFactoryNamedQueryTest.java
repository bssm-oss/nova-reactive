package io.nova.metadata;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.LockModeType;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.NamedNativeQueries;
import jakarta.persistence.NamedNativeQuery;
import jakarta.persistence.NamedQueries;
import jakarta.persistence.NamedQuery;
import jakarta.persistence.QueryHint;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link EntityMetadataFactory#namedQueryDefinitions(Class)}의 {@code @NamedQuery}/{@code @NamedNativeQuery}
 * 파싱 단위 테스트 — 단일/반복/컨테이너 선언, {@code @MappedSuperclass} 상속, 미지원 요소 fail-fast.
 */
class EntityMetadataFactoryNamedQueryTest {

    private final EntityMetadataFactory factory = new EntityMetadataFactory(new DefaultNamingStrategy());

    @Test
    void parsesSingleNamedJpqlQuery() {
        List<NamedQueryDefinition> definitions = factory.namedQueryDefinitions(SingleJpql.class);
        assertEquals(1, definitions.size());
        NamedQueryDefinition definition = definitions.get(0);
        assertEquals("SingleJpql.all", definition.name());
        assertEquals("SELECT e FROM SingleJpql e", definition.query());
        assertFalse(definition.nativeQuery());
        assertNull(definition.resultClass());
    }

    @Test
    void parsesRepeatedAndContainerNamedQueries() {
        List<NamedQueryDefinition> definitions = factory.namedQueryDefinitions(RepeatedQueries.class);
        assertEquals(3, definitions.size());
        assertTrue(definitions.stream().anyMatch(d -> d.name().equals("RepeatedQueries.byName") && !d.nativeQuery()));
        assertTrue(definitions.stream().anyMatch(d -> d.name().equals("RepeatedQueries.byAge") && !d.nativeQuery()));
        NamedQueryDefinition nativeDef = definitions.stream()
                .filter(NamedQueryDefinition::nativeQuery)
                .findFirst()
                .orElseThrow();
        assertEquals("RepeatedQueries.rawAll", nativeDef.name());
        assertEquals("SELECT * FROM repeated_queries", nativeDef.query());
        assertEquals(RepeatedQueries.class, nativeDef.resultClass());
    }

    @Test
    void nativeQueryWithoutResultClassHasNullResultClass() {
        List<NamedQueryDefinition> definitions = factory.namedQueryDefinitions(NativeNoResult.class);
        NamedQueryDefinition definition = definitions.get(0);
        assertTrue(definition.nativeQuery());
        assertNull(definition.resultClass());
    }

    @Test
    void inheritsNamedQueriesFromMappedSuperclass() {
        List<NamedQueryDefinition> definitions = factory.namedQueryDefinitions(SubEntity.class);
        assertTrue(definitions.stream().anyMatch(d -> d.name().equals("Base.baseQuery")));
        assertTrue(definitions.stream().anyMatch(d -> d.name().equals("Sub.subQuery")));
        // root-first 순서: superclass 선언이 먼저 온다.
        assertEquals("Base.baseQuery", definitions.get(0).name());
    }

    @Test
    void cachesDefinitionsPerType() {
        List<NamedQueryDefinition> first = factory.namedQueryDefinitions(SingleJpql.class);
        List<NamedQueryDefinition> second = factory.namedQueryDefinitions(SingleJpql.class);
        assertTrue(first == second, "definitions should be cached and returned as the same immutable list");
    }

    @Test
    void rejectsUnsupportedLockMode() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> factory.namedQueryDefinitions(WithLockMode.class));
        assertTrue(ex.getMessage().contains("lockMode"));
    }

    @Test
    void rejectsUnsupportedQueryHint() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> factory.namedQueryDefinitions(WithHint.class));
        assertTrue(ex.getMessage().contains("hints"));
    }

    @Test
    void rejectsUnsupportedResultSetMapping() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> factory.namedQueryDefinitions(WithResultSetMapping.class));
        assertTrue(ex.getMessage().contains("resultSetMapping"));
    }

    @Test
    void returnsEmptyForEntityWithoutNamedQueries() {
        assertTrue(factory.namedQueryDefinitions(SubEntity.Plain.class).isEmpty());
    }

    // ------------------------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------------------------

    @Entity
    @NamedQuery(name = "SingleJpql.all", query = "SELECT e FROM SingleJpql e")
    static class SingleJpql {
        @Id
        Long id;
    }

    @Entity
    @NamedQuery(name = "RepeatedQueries.byName", query = "SELECT e FROM RepeatedQueries e WHERE e.name = :name")
    @NamedQuery(name = "RepeatedQueries.byAge", query = "SELECT e FROM RepeatedQueries e WHERE e.age = :age")
    @NamedNativeQueries({
            @NamedNativeQuery(name = "RepeatedQueries.rawAll",
                    query = "SELECT * FROM repeated_queries", resultClass = RepeatedQueries.class)
    })
    static class RepeatedQueries {
        @Id
        Long id;
        String name;
        int age;
    }

    @Entity
    @NamedNativeQuery(name = "NativeNoResult.count", query = "SELECT COUNT(*) FROM native_no_result")
    static class NativeNoResult {
        @Id
        Long id;
    }

    @MappedSuperclass
    @NamedQuery(name = "Base.baseQuery", query = "SELECT e FROM SubEntity e")
    static class Base {
        @Id
        Long id;
    }

    @Entity
    @NamedQuery(name = "Sub.subQuery", query = "SELECT e FROM SubEntity e WHERE e.tag = :tag")
    static class SubEntity extends Base {
        String tag;

        @Entity
        static class Plain {
            @Id
            Long id;
        }
    }

    @Entity
    @NamedQuery(name = "WithLockMode.locked", query = "SELECT e FROM WithLockMode e",
            lockMode = LockModeType.PESSIMISTIC_WRITE)
    static class WithLockMode {
        @Id
        Long id;
    }

    @Entity
    @NamedQuery(name = "WithHint.hinted", query = "SELECT e FROM WithHint e",
            hints = @QueryHint(name = "org.hibernate.cacheable", value = "true"))
    static class WithHint {
        @Id
        Long id;
    }

    @Entity
    @NamedNativeQuery(name = "WithResultSetMapping.mapped",
            query = "SELECT * FROM with_result_set_mapping", resultSetMapping = "someMapping")
    static class WithResultSetMapping {
        @Id
        Long id;
    }
}
