package io.nova.r2dbc;

import reactor.core.publisher.Mono;

/**
 * Reactive probe that reports a {@link PoolHealth} snapshot for a connection pool or
 * a bare {@link io.r2dbc.spi.ConnectionFactory}.
 *
 * <p>Implementations must never throw synchronously: failures (driver errors, network
 * timeouts, acquire timeouts) are expected to surface either as a {@code PoolHealth}
 * with {@link PoolHealth#reachable()} set to {@code false} or as a {@code Mono} error,
 * depending on the implementation's contract.
 */
public interface PoolHealthProbe {

    /**
     * Returns a cold {@code Mono} that, on subscription, performs the implementation's
     * health check and emits a single {@link PoolHealth} snapshot.
     */
    Mono<PoolHealth> probe();
}
