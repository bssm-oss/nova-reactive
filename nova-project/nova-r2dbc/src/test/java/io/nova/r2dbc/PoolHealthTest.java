package io.nova.r2dbc;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PoolHealthTest {

    @Test
    void storesAllComponents() {
        PoolHealth health = new PoolHealth(5, 2, 3, true);

        assertEquals(5, health.totalConnections());
        assertEquals(2, health.activeConnections());
        assertEquals(3, health.idleConnections());
        assertTrue(health.reachable());
    }

    @Test
    void allowsZeroConnectionCountersForBestEffortReporting() {
        // Probes that cannot read live pool metrics report 0/0/0 and rely solely on reachable.
        PoolHealth health = new PoolHealth(0, 0, 0, true);

        assertEquals(0, health.totalConnections());
        assertTrue(health.reachable());
    }

    @Test
    void unreachableHealthIsRepresentable() {
        PoolHealth health = new PoolHealth(0, 0, 0, false);

        assertFalse(health.reachable());
    }

    @Test
    void rejectsNegativeTotalConnections() {
        assertThrows(IllegalArgumentException.class,
                () -> new PoolHealth(-1, 0, 0, true));
    }

    @Test
    void rejectsNegativeActiveConnections() {
        assertThrows(IllegalArgumentException.class,
                () -> new PoolHealth(1, -1, 0, true));
    }

    @Test
    void rejectsNegativeIdleConnections() {
        assertThrows(IllegalArgumentException.class,
                () -> new PoolHealth(1, 0, -1, true));
    }

    @Test
    void rejectsActiveExceedingTotal() {
        assertThrows(IllegalArgumentException.class,
                () -> new PoolHealth(2, 3, 0, true));
    }

    @Test
    void rejectsIdleExceedingTotal() {
        assertThrows(IllegalArgumentException.class,
                () -> new PoolHealth(2, 0, 3, true));
    }
}
