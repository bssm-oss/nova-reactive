package io.nova.dialect.h2;

import io.nova.sql.SchemaGenerator;
import io.nova.sql.SqlRenderer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class H2DialectTest {
    private final H2Dialect dialect = new H2Dialect();

    @Test
    void reportsH2Name() {
        assertEquals("h2", dialect.name());
    }

    @Test
    void usesPositionalQuestionMarkBindMarkers() {
        assertEquals("?", dialect.bindMarkers().marker(1));
        assertEquals("?", dialect.bindMarkers().marker(2));
        assertEquals("?", dialect.bindMarkers().marker(99));
    }

    @Test
    void quotesIdentifiersWithDoubleQuotes() {
        assertEquals("\"users\"", dialect.quote("users"));
        assertEquals("\"email_address\"", dialect.quote("email_address"));
    }

    @Test
    void exposesSqlRendererAndSchemaGeneratorAndReusesInstances() {
        SqlRenderer renderer = dialect.sqlRenderer();
        SchemaGenerator generator = dialect.schemaGenerator();
        assertNotNull(renderer);
        assertNotNull(generator);
        assertSame(renderer, dialect.sqlRenderer());
        assertSame(generator, dialect.schemaGenerator());
    }

    @Test
    void reportsUseOfReturningClauseForGeneratedKeys() {
        assertTrue(dialect.usesReturningForGeneratedKeys());
    }

    @Test
    void sequenceNextValueSqlIsUnsupportedByDefault() {
        UnsupportedOperationException exception = assertThrows(
                UnsupportedOperationException.class,
                () -> dialect.sequenceNextValueSql("seq")
        );
        assertTrue(exception.getMessage().contains("h2"));
    }
}
