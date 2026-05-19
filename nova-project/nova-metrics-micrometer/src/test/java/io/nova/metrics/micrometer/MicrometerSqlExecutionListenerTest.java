package io.nova.metrics.micrometer;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.nova.sql.SqlStatement;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MicrometerSqlExecutionListenerTest {

    private static final SqlStatement STATEMENT =
            new SqlStatement("SELECT 1", List.of());

    @Test
    void recordsSuccessTimerOnAfterExecution() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MicrometerSqlExecutionListener listener = new MicrometerSqlExecutionListener(registry);

        listener.onAfterExecution(STATEMENT, Duration.ofMillis(12), 1L);
        listener.onAfterExecution(STATEMENT, Duration.ofMillis(8), 3L);

        Timer timer = registry.find("nova.sql.query").tag("outcome", "success").timer();
        assertNotNull(timer, "expected success timer to be registered");
        assertEquals(2L, timer.count());
        assertEquals(20.0, timer.totalTime(java.util.concurrent.TimeUnit.MILLISECONDS), 0.001);

        assertNull(
                registry.find("nova.sql.errors").counter(),
                "no error counter should be registered on success"
        );
    }

    @Test
    void recordsErrorTimerAndCounterOnError() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MicrometerSqlExecutionListener listener = new MicrometerSqlExecutionListener(registry);
        IllegalStateException boom = new IllegalStateException("boom");

        listener.onError(STATEMENT, Duration.ofMillis(5), boom);

        Timer errorTimer = registry.find("nova.sql.query")
                .tag("outcome", "error")
                .tag("exception", "IllegalStateException")
                .timer();
        assertNotNull(errorTimer, "expected error timer to be registered");
        assertEquals(1L, errorTimer.count());

        Counter errorCounter = registry.find("nova.sql.errors")
                .tag("exception", "IllegalStateException")
                .counter();
        assertNotNull(errorCounter, "expected error counter to be registered");
        assertEquals(1.0, errorCounter.count(), 0.0001);
    }

    @Test
    void onBeforeExecutionDoesNotRegisterMeters() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MicrometerSqlExecutionListener listener = new MicrometerSqlExecutionListener(registry);

        listener.onBeforeExecution(STATEMENT);

        assertTrue(
                registry.getMeters().isEmpty(),
                "onBeforeExecution must not register any meters"
        );
    }

    @Test
    void honoursCustomMetricPrefix() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MicrometerSqlExecutionListener listener =
                new MicrometerSqlExecutionListener(registry, "app.db");

        listener.onAfterExecution(STATEMENT, Duration.ofMillis(4), 1L);
        listener.onError(STATEMENT, Duration.ofMillis(7), new RuntimeException("x"));

        assertNotNull(
                registry.find("app.db.query").tag("outcome", "success").timer(),
                "expected custom-prefix success timer"
        );
        assertNotNull(
                registry.find("app.db.query").tag("outcome", "error").timer(),
                "expected custom-prefix error timer"
        );
        assertNotNull(
                registry.find("app.db.errors").counter(),
                "expected custom-prefix error counter"
        );
        assertNull(
                registry.find("nova.sql.query").timer(),
                "default-prefix timer must not be registered when custom prefix is supplied"
        );
    }

    @Test
    void rejectsNullRegistry() {
        assertThrows(
                NullPointerException.class,
                () -> new MicrometerSqlExecutionListener(null)
        );
    }

    @Test
    void rejectsBlankMetricPrefix() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        assertThrows(
                IllegalArgumentException.class,
                () -> new MicrometerSqlExecutionListener(registry, "   ")
        );
    }
}
