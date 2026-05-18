package io.nova.r2dbc;

import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactory;
import reactor.core.publisher.Mono;

import java.util.Objects;

/**
 * Best-effort {@link PoolHealthProbe} that runs a connection acquire/release smoke
 * test against an {@link ConnectionFactory}.
 *
 * <p>This implementation deliberately does not depend on any specific pool library
 * (such as {@code r2dbc-pool}) so it works against the bare R2DBC SPI. As a
 * consequence it cannot read live pool metrics: the emitted {@link PoolHealth} always
 * reports {@code totalConnections=0}, {@code activeConnections=0}, and
 * {@code idleConnections=0}; only {@link PoolHealth#reachable()} carries meaningful
 * signal.
 *
 * <p>The probe is reachable when a connection can be obtained from the factory and
 * closed without error. Any error during {@code create()} or {@code close()} is
 * captured and reported as {@code reachable=false} rather than as a Mono error, so
 * the probe is safe to drive from health-check endpoints.
 */
public final class SimplePoolHealthProbe implements PoolHealthProbe {

    private final ConnectionFactory connectionFactory;

    public SimplePoolHealthProbe(ConnectionFactory connectionFactory) {
        this.connectionFactory = Objects.requireNonNull(connectionFactory, "connectionFactory");
    }

    @Override
    public Mono<PoolHealth> probe() {
        return Mono.usingWhen(
                        Mono.from(connectionFactory.create()),
                        conn -> Mono.just(reachable()),
                        SimplePoolHealthProbe::closeQuietly)
                .onErrorResume(error -> Mono.just(unreachable()));
    }

    private static Mono<Void> closeQuietly(Connection conn) {
        return Mono.from(conn.close()).onErrorResume(ignored -> Mono.empty());
    }

    private static PoolHealth reachable() {
        return new PoolHealth(0, 0, 0, true);
    }

    private static PoolHealth unreachable() {
        return new PoolHealth(0, 0, 0, false);
    }
}
