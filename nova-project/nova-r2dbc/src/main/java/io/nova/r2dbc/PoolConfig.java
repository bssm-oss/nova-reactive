package io.nova.r2dbc;

import java.time.Duration;
import java.util.Objects;

/**
 * R2DBC connection pool configuration parameters.
 *
 * <p>Nova-r2dbc does not bundle a pool implementation by default; this record carries
 * intent that adapter code (or downstream pool integrations such as {@code r2dbc-pool})
 * can consume. The record is purely a value carrier and performs validation in the
 * compact constructor.
 *
 * @param initialSize    number of connections to allocate eagerly; must be {@code >= 0}
 * @param maxSize        upper bound on simultaneously held connections; must be {@code >= 1}
 *                       and {@code >= initialSize}
 * @param maxIdleTime    maximum duration a connection may sit idle before being evicted;
 *                       must be non-null and non-negative
 * @param acquireTimeout maximum duration a caller will wait to acquire a connection;
 *                       must be non-null and non-negative
 */
public record PoolConfig(
        int initialSize,
        int maxSize,
        Duration maxIdleTime,
        Duration acquireTimeout
) {
    public PoolConfig {
        if (initialSize < 0) {
            throw new IllegalArgumentException("initialSize must be >= 0, got " + initialSize);
        }
        if (maxSize < 1) {
            throw new IllegalArgumentException("maxSize must be >= 1, got " + maxSize);
        }
        if (maxSize < initialSize) {
            throw new IllegalArgumentException(
                    "maxSize (" + maxSize + ") must be >= initialSize (" + initialSize + ")");
        }
        Objects.requireNonNull(maxIdleTime, "maxIdleTime");
        Objects.requireNonNull(acquireTimeout, "acquireTimeout");
        if (maxIdleTime.isNegative()) {
            throw new IllegalArgumentException("maxIdleTime must be non-negative, got " + maxIdleTime);
        }
        if (acquireTimeout.isNegative()) {
            throw new IllegalArgumentException("acquireTimeout must be non-negative, got " + acquireTimeout);
        }
    }

    /**
     * Reasonable defaults suitable for small-to-medium reactive applications:
     * {@code initialSize=1}, {@code maxSize=10}, {@code maxIdleTime=PT30M},
     * {@code acquireTimeout=PT30S}.
     */
    public static PoolConfig defaults() {
        return new PoolConfig(1, 10, Duration.ofMinutes(30), Duration.ofSeconds(30));
    }

    /**
     * Convenience factory taking only sizing parameters and applying default timeouts
     * from {@link #defaults()}.
     */
    public static PoolConfig of(int initialSize, int maxSize) {
        PoolConfig defaults = defaults();
        return new PoolConfig(initialSize, maxSize, defaults.maxIdleTime(), defaults.acquireTimeout());
    }
}
