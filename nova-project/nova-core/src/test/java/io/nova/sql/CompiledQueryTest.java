package io.nova.sql;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CompiledQueryTest {
    private static final String SQL = "select id, email_address from accounts where email_address = ? and active = ?";

    @Test
    void exposesPreRenderedSqlAndParameterCount() {
        CompiledQuery compiled = new SimpleCompiledQuery(SQL, 2);

        assertEquals(SQL, compiled.sql());
        assertEquals(2, compiled.parameterCount());
    }

    @Test
    void bindProducesStatementWithGivenValues() {
        CompiledQuery compiled = new SimpleCompiledQuery(SQL, 2);

        SqlStatement statement = compiled.bind("a@nova.io", true);

        assertEquals(SQL, statement.sql());
        assertEquals(List.of("a@nova.io", true), statement.bindings());
    }

    @Test
    void bindListProducesStatementWithGivenValues() {
        CompiledQuery compiled = new SimpleCompiledQuery(SQL, 2);

        SqlStatement statement = compiled.bindList(List.of("b@nova.io", false));

        assertEquals(SQL, statement.sql());
        assertEquals(List.of("b@nova.io", false), statement.bindings());
    }

    @Test
    void bindIsRepeatableWithDifferentValues() {
        CompiledQuery compiled = new SimpleCompiledQuery(SQL, 2);

        SqlStatement first = compiled.bind("a@nova.io", true);
        SqlStatement second = compiled.bind("b@nova.io", false);

        assertEquals(SQL, first.sql());
        assertEquals(SQL, second.sql());
        assertEquals(List.of("a@nova.io", true), first.bindings());
        assertEquals(List.of("b@nova.io", false), second.bindings());
        assertNotSame(first, second);
    }

    @Test
    void bindRejectsTooFewValues() {
        CompiledQuery compiled = new SimpleCompiledQuery(SQL, 2);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> compiled.bind("only one")
        );

        assertEquals("Expected 2 binding(s) but received 1", exception.getMessage());
    }

    @Test
    void bindRejectsTooManyValues() {
        CompiledQuery compiled = new SimpleCompiledQuery(SQL, 2);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> compiled.bind("a", true, "extra")
        );

        assertEquals("Expected 2 binding(s) but received 3", exception.getMessage());
    }

    @Test
    void bindListRejectsTooFewValues() {
        CompiledQuery compiled = new SimpleCompiledQuery(SQL, 2);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> compiled.bindList(List.of("only one"))
        );

        assertEquals("Expected 2 binding(s) but received 1", exception.getMessage());
    }

    @Test
    void bindListRejectsTooManyValues() {
        CompiledQuery compiled = new SimpleCompiledQuery(SQL, 2);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> compiled.bindList(List.of("a", true, "extra"))
        );

        assertEquals("Expected 2 binding(s) but received 3", exception.getMessage());
    }

    @Test
    void supportsParameterlessSql() {
        CompiledQuery compiled = new SimpleCompiledQuery("select 1", 0);

        SqlStatement statement = compiled.bind();

        assertEquals("select 1", statement.sql());
        assertEquals(List.of(), statement.bindings());
    }

    @Test
    void parameterCountRejectsNegative() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new SimpleCompiledQuery(SQL, -1)
        );

        assertEquals("parameterCount must not be negative", exception.getMessage());
    }

    @Test
    void constructorRejectsNullSql() {
        assertThrows(NullPointerException.class, () -> new SimpleCompiledQuery(null, 0));
    }

    @Test
    void bindRejectsNullValuesArray() {
        CompiledQuery compiled = new SimpleCompiledQuery(SQL, 2);

        assertThrows(NullPointerException.class, () -> compiled.bind((Object[]) null));
    }

    @Test
    void bindListRejectsNullValuesList() {
        CompiledQuery compiled = new SimpleCompiledQuery(SQL, 2);

        assertThrows(NullPointerException.class, () -> compiled.bindList(null));
    }
}
