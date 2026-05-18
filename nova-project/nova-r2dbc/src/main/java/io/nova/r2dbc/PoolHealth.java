package io.nova.r2dbc;

/**
 * Snapshot of a connection pool's observable state at the moment {@link PoolHealthProbe#probe()}
 * resolved.
 *
 * <p>When a probe implementation cannot read live pool metrics (for example, because Nova is
 * running against a bare {@code ConnectionFactory} without an actual pool implementation),
 * the connection counters are reported as zero and only {@link #reachable()} carries
 * meaningful signal.
 *
 * @param totalConnections   total connections currently held by the pool, including active and idle
 * @param activeConnections  connections currently leased to callers; must be {@code <= totalConnections}
 * @param idleConnections    connections sitting idle and available for lease; must be {@code <= totalConnections}
 * @param reachable          {@code true} if the most recent acquire/release smoke test succeeded
 */
public record PoolHealth(
        int totalConnections,
        int activeConnections,
        int idleConnections,
        boolean reachable
) {
    public PoolHealth {
        if (totalConnections < 0) {
            throw new IllegalArgumentException("totalConnections must be >= 0, got " + totalConnections);
        }
        if (activeConnections < 0) {
            throw new IllegalArgumentException("activeConnections must be >= 0, got " + activeConnections);
        }
        if (idleConnections < 0) {
            throw new IllegalArgumentException("idleConnections must be >= 0, got " + idleConnections);
        }
        if (activeConnections > totalConnections) {
            throw new IllegalArgumentException(
                    "activeConnections (" + activeConnections + ") must be <= totalConnections (" + totalConnections + ")");
        }
        if (idleConnections > totalConnections) {
            throw new IllegalArgumentException(
                    "idleConnections (" + idleConnections + ") must be <= totalConnections (" + totalConnections + ")");
        }
    }
}
