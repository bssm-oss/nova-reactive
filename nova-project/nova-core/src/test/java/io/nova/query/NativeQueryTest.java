package io.nova.query;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeQueryTest {
    @Test
    void ofRejectsNullSql() {
        assertThrows(NullPointerException.class, () -> NativeQuery.of(null));
    }

    @Test
    void ofRejectsBlankSql() {
        assertThrows(IllegalArgumentException.class, () -> NativeQuery.of(""));
        assertThrows(IllegalArgumentException.class, () -> NativeQuery.of("   "));
    }

    @Test
    void ofWithoutBindingsHoldsEmptyList() {
        NativeQuery query = NativeQuery.of("select 1");

        assertEquals("select 1", query.sql());
        assertTrue(query.bindings().isEmpty());
    }

    @Test
    void ofRejectsNullBindingList() {
        assertThrows(NullPointerException.class, () -> NativeQuery.of("select 1", null));
    }

    @Test
    void ofTakesDefensiveCopyOfBindings() {
        List<Object> source = new ArrayList<>();
        source.add("a");
        source.add(1);
        NativeQuery query = NativeQuery.of("select ?, ?", source);
        source.add("mutated");

        assertEquals(List.of("a", 1), query.bindings());
    }

    @Test
    void bindReturnsNewInstanceAndPreservesOriginal() {
        NativeQuery base = NativeQuery.of("select ?, ?");
        NativeQuery withOne = base.bind("a");
        NativeQuery withTwo = withOne.bind(2);

        assertNotSame(base, withOne);
        assertNotSame(withOne, withTwo);
        assertTrue(base.bindings().isEmpty());
        assertEquals(List.of("a"), withOne.bindings());
        assertEquals(List.of("a", 2), withTwo.bindings());
        assertEquals(base.sql(), withTwo.sql());
    }

    @Test
    void bindAllowsNullValue() {
        NativeQuery query = NativeQuery.of("select ?").bind(null);

        assertEquals(1, query.bindings().size());
        assertEquals(null, query.bindings().get(0));
    }

    @Test
    void bindingsViewIsUnmodifiable() {
        NativeQuery query = NativeQuery.of("select ?", List.of("a"));

        assertThrows(UnsupportedOperationException.class, () -> query.bindings().add("b"));
    }
}
