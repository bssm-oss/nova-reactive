package io.nova.query;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CursorTest {

    @Test
    void cursorFieldAscFactoryProducesAscDirection() {
        CursorField field = CursorField.asc("id", 10L);

        assertEquals("id", field.property());
        assertEquals(10L, field.lastValue());
        assertEquals(Sort.Direction.ASC, field.direction());
    }

    @Test
    void cursorFieldDescFactoryProducesDescDirection() {
        CursorField field = CursorField.desc("createdAt", "2026-05-19");

        assertEquals(Sort.Direction.DESC, field.direction());
    }

    @Test
    void cursorFieldRejectsNullProperty() {
        assertThrows(NullPointerException.class, () -> new CursorField(null, 1L, Sort.Direction.ASC));
    }

    @Test
    void cursorFieldRejectsBlankProperty() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new CursorField("  ", 1L, Sort.Direction.ASC)
        );
        assertEquals("property must not be blank", exception.getMessage());
    }

    @Test
    void cursorFieldRejectsNullLastValue() {
        assertThrows(NullPointerException.class, () -> new CursorField("id", null, Sort.Direction.ASC));
    }

    @Test
    void cursorFieldRejectsNullDirection() {
        assertThrows(NullPointerException.class, () -> new CursorField("id", 1L, null));
    }

    @Test
    void cursorOfRetainsOrderOfFields() {
        Cursor cursor = Cursor.of(
                CursorField.desc("createdAt", "T1"),
                CursorField.asc("id", 100L)
        );

        assertEquals(2, cursor.fields().size());
        assertEquals("createdAt", cursor.fields().get(0).property());
        assertEquals("id", cursor.fields().get(1).property());
    }

    @Test
    void cursorRejectsEmptyFields() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new Cursor(List.of())
        );
        assertEquals("Cursor requires at least one field", exception.getMessage());
    }

    @Test
    void cursorRejectsNullFieldsList() {
        assertThrows(NullPointerException.class, () -> new Cursor(null));
    }

    @Test
    void cursorRejectsNullElementInFields() {
        List<CursorField> withNull = new ArrayList<>();
        withNull.add(CursorField.asc("id", 1L));
        withNull.add(null);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new Cursor(withNull)
        );
        assertEquals("Cursor field at index 1 is null", exception.getMessage());
    }

    @Test
    void cursorDefensivelyCopiesFieldsSoLaterMutationsDoNotLeak() {
        List<CursorField> source = new ArrayList<>();
        source.add(CursorField.asc("id", 1L));
        Cursor cursor = new Cursor(source);

        source.add(CursorField.asc("email", "x@nova.io"));

        assertEquals(1, cursor.fields().size());
        assertNotSame(source, cursor.fields());
    }

    @Test
    void cursorFieldsListIsUnmodifiable() {
        Cursor cursor = Cursor.of(CursorField.asc("id", 1L));

        assertThrows(UnsupportedOperationException.class,
                () -> cursor.fields().add(CursorField.asc("email", "x")));
    }

    @Test
    void cursorOfRejectsZeroVarargs() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                Cursor::of
        );
        assertEquals("Cursor requires at least one field", exception.getMessage());
        assertTrue(true);
    }
}
