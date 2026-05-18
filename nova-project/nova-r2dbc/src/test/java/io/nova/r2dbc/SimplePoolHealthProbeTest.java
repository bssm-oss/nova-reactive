package io.nova.r2dbc;

import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SimplePoolHealthProbe의 acquire/release smoke test 경로를 r2dbc-h2 in-memory DB와
 * 가벼운 ConnectionFactory test double로 검증한다.
 */
class SimplePoolHealthProbeTest {

    private ConnectionFactory connectionFactory;

    @BeforeEach
    void setUp() {
        String dbName = "probe_" + UUID.randomUUID().toString().replace("-", "");
        connectionFactory = ConnectionFactories.get("r2dbc:h2:mem:///" + dbName + "?DB_CLOSE_DELAY=-1");
    }

    @Test
    void rejectsNullConnectionFactory() {
        assertThrows(NullPointerException.class, () -> new SimplePoolHealthProbe(null));
    }

    @Test
    void probeReportsReachableAndZeroCountersWhenConnectionCanBeAcquired() {
        SimplePoolHealthProbe probe = new SimplePoolHealthProbe(connectionFactory);

        StepVerifier.create(probe.probe())
                .assertNext(health -> {
                    assertTrue(health.reachable(), "live H2 factory should be reachable");
                    assertEquals(0, health.totalConnections(),
                            "best-effort probe cannot read live metrics; totals must be 0");
                    assertEquals(0, health.activeConnections());
                    assertEquals(0, health.idleConnections());
                })
                .verifyComplete();
    }

    @Test
    void probeAcquiresOneConnectionPerInvocation() {
        AtomicInteger createCount = new AtomicInteger();
        ConnectionFactory counting = countingFactory(connectionFactory, createCount);
        SimplePoolHealthProbe probe = new SimplePoolHealthProbe(counting);

        StepVerifier.create(probe.probe())
                .assertNext(health -> assertTrue(health.reachable()))
                .verifyComplete();

        assertEquals(1, createCount.get(), "single probe must acquire exactly one connection");
    }

    @Test
    void probeReportsUnreachableInsteadOfErroringWhenFactoryFails() {
        SimplePoolHealthProbe probe = new SimplePoolHealthProbe(failingFactory());

        StepVerifier.create(probe.probe())
                .assertNext(health -> {
                    assertFalse(health.reachable(), "factory error must surface as unreachable");
                    assertEquals(0, health.totalConnections());
                })
                .verifyComplete();
    }

    @Test
    void probeIsColdAndCanBeSubscribedMultipleTimes() {
        AtomicInteger createCount = new AtomicInteger();
        ConnectionFactory counting = countingFactory(connectionFactory, createCount);
        SimplePoolHealthProbe probe = new SimplePoolHealthProbe(counting);

        StepVerifier.create(probe.probe())
                .expectNextCount(1)
                .verifyComplete();
        StepVerifier.create(probe.probe())
                .expectNextCount(1)
                .verifyComplete();

        assertEquals(2, createCount.get(), "each probe() call must acquire its own connection");
    }

    /**
     * ConnectionFactory.create() 호출 횟수를 세는 가벼운 wrapper — 실제 connection은 그대로 위임한다.
     */
    private static ConnectionFactory countingFactory(ConnectionFactory delegate, AtomicInteger createCount) {
        return new ConnectionFactory() {
            @Override
            public Publisher<? extends Connection> create() {
                createCount.incrementAndGet();
                return Mono.from(delegate.create());
            }

            @Override
            public ConnectionFactoryMetadata getMetadata() {
                return delegate.getMetadata();
            }
        };
    }

    /**
     * create()가 항상 에러를 emit하는 factory — 도달 불가능한 풀을 시뮬레이션한다.
     */
    private static ConnectionFactory failingFactory() {
        return new ConnectionFactory() {
            @Override
            public Publisher<? extends Connection> create() {
                return Mono.error(new RuntimeException("simulated acquire failure"));
            }

            @Override
            public ConnectionFactoryMetadata getMetadata() {
                return () -> "failing";
            }
        };
    }
}
