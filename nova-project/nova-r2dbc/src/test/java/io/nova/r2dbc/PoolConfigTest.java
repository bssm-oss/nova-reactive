package io.nova.r2dbc;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PoolConfigTest {

    @Test
    void defaultsExposeSaneSizingAndTimeouts() {
        PoolConfig config = PoolConfig.defaults();

        assertEquals(1, config.initialSize());
        assertEquals(10, config.maxSize());
        assertEquals(Duration.ofMinutes(30), config.maxIdleTime());
        assertEquals(Duration.ofSeconds(30), config.acquireTimeout());
    }

    @Test
    void ofInheritsDefaultTimeoutsAndAppliesGivenSizes() {
        PoolConfig config = PoolConfig.of(2, 8);

        assertEquals(2, config.initialSize());
        assertEquals(8, config.maxSize());
        assertEquals(PoolConfig.defaults().maxIdleTime(), config.maxIdleTime());
        assertEquals(PoolConfig.defaults().acquireTimeout(), config.acquireTimeout());
    }

    @Test
    void rejectsNegativeInitialSize() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new PoolConfig(-1, 10, Duration.ZERO, Duration.ZERO));
        assertNotNull(ex.getMessage());
    }

    @Test
    void rejectsZeroMaxSize() {
        assertThrows(IllegalArgumentException.class,
                () -> new PoolConfig(0, 0, Duration.ZERO, Duration.ZERO));
    }

    @Test
    void rejectsMaxSizeBelowInitialSize() {
        assertThrows(IllegalArgumentException.class,
                () -> new PoolConfig(5, 3, Duration.ZERO, Duration.ZERO));
    }

    @Test
    void rejectsNullMaxIdleTime() {
        assertThrows(NullPointerException.class,
                () -> new PoolConfig(1, 10, null, Duration.ZERO));
    }

    @Test
    void rejectsNullAcquireTimeout() {
        assertThrows(NullPointerException.class,
                () -> new PoolConfig(1, 10, Duration.ZERO, null));
    }

    @Test
    void rejectsNegativeMaxIdleTime() {
        assertThrows(IllegalArgumentException.class,
                () -> new PoolConfig(1, 10, Duration.ofSeconds(-1), Duration.ZERO));
    }

    @Test
    void rejectsNegativeAcquireTimeout() {
        assertThrows(IllegalArgumentException.class,
                () -> new PoolConfig(1, 10, Duration.ZERO, Duration.ofSeconds(-1)));
    }

    @Test
    void zeroDurationsAreAccepted() {
        PoolConfig config = new PoolConfig(0, 1, Duration.ZERO, Duration.ZERO);

        assertEquals(0, config.initialSize());
        assertEquals(1, config.maxSize());
    }
}
