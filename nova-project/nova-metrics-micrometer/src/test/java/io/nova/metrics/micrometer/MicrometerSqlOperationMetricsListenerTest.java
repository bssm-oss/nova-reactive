package io.nova.metrics.micrometer;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.nova.sql.SqlStatement;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MicrometerSqlOperationMetricsListenerTest {

    private static final SqlStatement INSERT_STATEMENT =
            new SqlStatement("INSERT INTO users (id, name) VALUES (?, ?)", List.of(1L, "ada"));
    private static final SqlStatement UPDATE_STATEMENT =
            new SqlStatement("UPDATE users SET name = ? WHERE id = ?", List.of("ada2", 1L));
    private static final SqlStatement SELECT_STATEMENT =
            new SqlStatement("SELECT * FROM users WHERE id = ?", List.of(1L));
    private static final SqlStatement DELETE_STATEMENT =
            new SqlStatement("DELETE FROM users WHERE id = ?", List.of(1L));
    private static final SqlStatement DDL_STATEMENT =
            new SqlStatement("CREATE TABLE users (id BIGINT PRIMARY KEY)", List.of());

    @Test
    void recordsSaveOperationForInsertAndUpdateStatements() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MicrometerSqlOperationMetricsListener listener =
                new MicrometerSqlOperationMetricsListener(registry, Duration.ofSeconds(1));

        listener.onAfterExecution(INSERT_STATEMENT, Duration.ofMillis(5), 1L);
        listener.onAfterExecution(UPDATE_STATEMENT, Duration.ofMillis(7), 1L);

        Timer timer = registry.find("nova.sql.operation.duration")
                .tag("operation", "save")
                .tag("outcome", "success")
                .timer();
        assertNotNull(timer, "expected save operation timer");
        assertEquals(2L, timer.count());
        assertEquals(12.0, timer.totalTime(TimeUnit.MILLISECONDS), 0.001);

        Counter counter = registry.find("nova.sql.operation.count").tag("operation", "save").counter();
        assertNotNull(counter, "expected save operation counter");
        assertEquals(2.0, counter.count(), 0.0001);
    }

    @Test
    void recordsFindOperationForSelectStatement() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MicrometerSqlOperationMetricsListener listener =
                new MicrometerSqlOperationMetricsListener(registry, Duration.ofSeconds(1));

        listener.onAfterExecution(SELECT_STATEMENT, Duration.ofMillis(3), 1L);

        assertNotNull(
                registry.find("nova.sql.operation.duration")
                        .tag("operation", "find")
                        .tag("outcome", "success")
                        .timer(),
                "expected find operation timer");
        assertEquals(
                1.0,
                registry.find("nova.sql.operation.count").tag("operation", "find").counter().count(),
                0.0001);
    }

    @Test
    void recordsDeleteOperationForDeleteStatement() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MicrometerSqlOperationMetricsListener listener =
                new MicrometerSqlOperationMetricsListener(registry, Duration.ofSeconds(1));

        listener.onAfterExecution(DELETE_STATEMENT, Duration.ofMillis(2), 1L);

        assertNotNull(
                registry.find("nova.sql.operation.duration")
                        .tag("operation", "delete")
                        .tag("outcome", "success")
                        .timer(),
                "expected delete operation timer");
        assertEquals(
                1.0,
                registry.find("nova.sql.operation.count").tag("operation", "delete").counter().count(),
                0.0001);
    }

    @Test
    void fallsBackToQueryOperationForUnclassifiedSql() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MicrometerSqlOperationMetricsListener listener =
                new MicrometerSqlOperationMetricsListener(registry, Duration.ofSeconds(1));

        listener.onAfterExecution(DDL_STATEMENT, Duration.ofMillis(9), 0L);

        assertNotNull(
                registry.find("nova.sql.operation.duration")
                        .tag("operation", "query")
                        .tag("outcome", "success")
                        .timer(),
                "expected query fallback operation timer for unrecognized SQL");
    }

    @Test
    void incrementsSlowCounterWhenElapsedMeetsThreshold() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MicrometerSqlOperationMetricsListener listener =
                new MicrometerSqlOperationMetricsListener(registry, Duration.ofMillis(100));

        listener.onAfterExecution(SELECT_STATEMENT, Duration.ofMillis(150), 1L);

        Counter slowCounter = registry.find("nova.sql.operation.slow")
                .tag("operation", "find")
                .tag("outcome", "success")
                .counter();
        assertNotNull(slowCounter, "expected slow counter to be registered when threshold is met");
        assertEquals(1.0, slowCounter.count(), 0.0001);
    }

    @Test
    void doesNotIncrementSlowCounterWhenBelowThreshold() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MicrometerSqlOperationMetricsListener listener =
                new MicrometerSqlOperationMetricsListener(registry, Duration.ofMillis(100));

        listener.onAfterExecution(SELECT_STATEMENT, Duration.ofMillis(50), 1L);

        assertNull(
                registry.find("nova.sql.operation.slow").tag("operation", "find").counter(),
                "slow counter must not be registered below threshold");
    }

    @Test
    void elapsedExactlyAtThresholdCountsAsSlow() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MicrometerSqlOperationMetricsListener listener =
                new MicrometerSqlOperationMetricsListener(registry, Duration.ofMillis(100));

        listener.onAfterExecution(SELECT_STATEMENT, Duration.ofMillis(100), 1L);

        assertNotNull(
                registry.find("nova.sql.operation.slow").tag("operation", "find").counter(),
                "elapsed == threshold must count as slow (inclusive boundary)");
    }

    @Test
    void recordsErrorOutcomeWithOperationTagAndSlowCounter() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MicrometerSqlOperationMetricsListener listener =
                new MicrometerSqlOperationMetricsListener(registry, Duration.ofMillis(50));
        IllegalStateException boom = new IllegalStateException("boom");

        listener.onError(DELETE_STATEMENT, Duration.ofMillis(80), boom);

        Timer errorTimer = registry.find("nova.sql.operation.duration")
                .tag("operation", "delete")
                .tag("outcome", "error")
                .timer();
        assertNotNull(errorTimer, "expected error-outcome operation timer");
        assertEquals(1L, errorTimer.count());

        assertEquals(
                1.0,
                registry.find("nova.sql.operation.count").tag("operation", "delete").counter().count(),
                0.0001,
                "operation count must increment on error too");

        assertNotNull(
                registry.find("nova.sql.operation.slow")
                        .tag("operation", "delete")
                        .tag("outcome", "error")
                        .counter(),
                "slow counter should also apply to slow errored executions");
    }

    @Test
    void rejectsNullErrorOnOnError() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MicrometerSqlOperationMetricsListener listener =
                new MicrometerSqlOperationMetricsListener(registry, Duration.ofSeconds(1));
        assertThrows(
                NullPointerException.class,
                () -> listener.onError(SELECT_STATEMENT, Duration.ofMillis(1), null));
    }

    @Test
    void onBeforeExecutionDoesNotRegisterMeters() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MicrometerSqlOperationMetricsListener listener =
                new MicrometerSqlOperationMetricsListener(registry, Duration.ofSeconds(1));

        listener.onBeforeExecution(SELECT_STATEMENT);

        assertTrue(registry.getMeters().isEmpty(), "onBeforeExecution must not register any meters");
    }

    @Test
    void honoursCustomMetricPrefix() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MicrometerSqlOperationMetricsListener listener =
                new MicrometerSqlOperationMetricsListener(registry, "app.db", Duration.ofSeconds(1));

        listener.onAfterExecution(INSERT_STATEMENT, Duration.ofMillis(4), 1L);

        assertNotNull(registry.find("app.db.operation.duration").tag("operation", "save").timer());
        assertNotNull(registry.find("app.db.operation.count").tag("operation", "save").counter());
        assertNull(
                registry.find("nova.sql.operation.duration").timer(),
                "default-prefix timer must not be registered when custom prefix is supplied");
    }

    @Test
    void rejectsNullRegistry() {
        assertThrows(
                NullPointerException.class,
                () -> new MicrometerSqlOperationMetricsListener(null, Duration.ofSeconds(1)));
    }

    @Test
    void rejectsBlankMetricPrefix() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        assertThrows(
                IllegalArgumentException.class,
                () -> new MicrometerSqlOperationMetricsListener(registry, "   ", Duration.ofSeconds(1)));
    }

    @Test
    void rejectsNullSlowQueryThreshold() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        assertThrows(
                NullPointerException.class,
                () -> new MicrometerSqlOperationMetricsListener(registry, null));
    }

    @Test
    void rejectsNegativeSlowQueryThreshold() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        assertThrows(
                IllegalArgumentException.class,
                () -> new MicrometerSqlOperationMetricsListener(registry, Duration.ofMillis(-1)));
    }

    @Test
    void endToEndSaveThenFindIncrementsOperationMetricsThroughCompositeListener() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MicrometerSqlOperationMetricsListener operationMetrics =
                new MicrometerSqlOperationMetricsListener(registry, Duration.ofMillis(100));
        MicrometerSqlExecutionListener overallMetrics = new MicrometerSqlExecutionListener(registry);
        io.nova.core.SqlExecutionListener composite =
                io.nova.core.CompositeSqlExecutionListener.of(operationMetrics, overallMetrics);

        // simulate save() -> INSERT flush
        composite.onBeforeExecution(INSERT_STATEMENT);
        composite.onAfterExecution(INSERT_STATEMENT, Duration.ofMillis(6), 1L);

        // simulate findById() -> SELECT
        composite.onBeforeExecution(SELECT_STATEMENT);
        composite.onAfterExecution(SELECT_STATEMENT, Duration.ofMillis(4), 1L);
        composite.onAfterExecution(SELECT_STATEMENT, Duration.ofMillis(9), 1L);

        assertEquals(
                1L,
                registry.find("nova.sql.operation.duration")
                        .tag("operation", "save")
                        .tag("outcome", "success")
                        .timer()
                        .count());
        assertEquals(
                2L,
                registry.find("nova.sql.operation.duration")
                        .tag("operation", "find")
                        .tag("outcome", "success")
                        .timer()
                        .count());
        assertEquals(
                1.0,
                registry.find("nova.sql.operation.count").tag("operation", "save").counter().count(),
                0.0001);
        assertEquals(
                2.0,
                registry.find("nova.sql.operation.count").tag("operation", "find").counter().count(),
                0.0001);

        // MicrometerSqlExecutionListener's pre-existing generic query timer must still see all 3 calls
        Timer overallTimer = registry.find("nova.sql.query").tag("outcome", "success").timer();
        assertNotNull(overallTimer);
        assertEquals(3L, overallTimer.count());
    }
}
