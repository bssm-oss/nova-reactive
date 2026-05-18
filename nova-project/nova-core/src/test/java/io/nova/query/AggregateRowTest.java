package io.nova.query;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AggregateRowTest {
    @Test
    void getReturnsValueByColumnName() {
        LinkedHashMap<String, Object> values = new LinkedHashMap<>();
        values.put("active", true);
        values.put("count", 7L);
        AggregateRow row = new AggregateRow(values);

        assertEquals(true, row.get("active"));
        assertEquals(7L, row.get("count"));
        assertNull(row.get("missing"));
    }

    @Test
    void typedGetReturnsValueOrNullAndChecksType() {
        LinkedHashMap<String, Object> values = new LinkedHashMap<>();
        values.put("count", 7L);
        values.put("missing", null);
        AggregateRow row = new AggregateRow(values);

        assertEquals(7L, row.get("count", Long.class));
        assertNull(row.get("missing", Long.class));
        assertNull(row.get("absent", Long.class));
        assertThrows(ClassCastException.class, () -> row.get("count", String.class));
    }

    @Test
    void asMapReturnsImmutableSnapshotInInsertionOrder() {
        LinkedHashMap<String, Object> values = new LinkedHashMap<>();
        values.put("active", true);
        values.put("count", 7L);
        AggregateRow row = new AggregateRow(values);

        assertEquals(List.of("active", "count"), List.copyOf(row.asMap().keySet()));
        assertThrows(UnsupportedOperationException.class, () -> row.asMap().put("more", 1L));
    }

    @Test
    void constructorCopiesInputSoLaterMutationsDoNotLeak() {
        LinkedHashMap<String, Object> values = new LinkedHashMap<>();
        values.put("count", 7L);
        AggregateRow row = new AggregateRow(values);
        values.put("count", 99L);
        values.put("extra", "x");

        assertEquals(7L, row.get("count"));
        assertNull(row.get("extra"));
    }

    @Test
    void equalityIsValueBased() {
        LinkedHashMap<String, Object> a = new LinkedHashMap<>();
        a.put("count", 7L);
        LinkedHashMap<String, Object> b = new LinkedHashMap<>();
        b.put("count", 7L);
        LinkedHashMap<String, Object> c = new LinkedHashMap<>();
        c.put("count", 8L);

        assertEquals(new AggregateRow(a), new AggregateRow(b));
        assertNotEquals(new AggregateRow(a), new AggregateRow(c));
    }

    @Test
    void rejectsNullColumnInGet() {
        AggregateRow row = new AggregateRow(new LinkedHashMap<>());
        assertThrows(NullPointerException.class, () -> row.get(null));
        assertThrows(NullPointerException.class, () -> row.get(null, Long.class));
        assertThrows(NullPointerException.class, () -> row.get("count", null));
    }

    @Test
    void toStringIsNotEmpty() {
        LinkedHashMap<String, Object> values = new LinkedHashMap<>();
        values.put("count", 7L);
        assertTrue(new AggregateRow(values).toString().contains("count"));
    }
}
