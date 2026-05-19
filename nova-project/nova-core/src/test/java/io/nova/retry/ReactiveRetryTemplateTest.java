package io.nova.retry;

import io.nova.exception.OptimisticLockingFailureException;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReactiveRetryTemplateTest {

    private static ReactiveRetryTemplate fastTemplate(int maxAttempts) {
        return ReactiveRetryTemplate.builder()
                .maxAttempts(maxAttempts)
                .initialBackoff(Duration.ofMillis(1))
                .backoffMultiplier(2.0)
                .maxBackoff(Duration.ofMillis(10))
                .retryable(OptimisticLockingFailureException.class::isInstance)
                .build();
    }

    @Test
    void executesOnceWhenNoError() {
        AtomicInteger calls = new AtomicInteger();
        ReactiveRetryTemplate template = fastTemplate(3);

        Mono<String> source = Mono.fromCallable(() -> {
            calls.incrementAndGet();
            return "ok";
        });

        StepVerifier.create(template.execute(source))
                .expectNext("ok")
                .verifyComplete();

        assertEquals(1, calls.get());
    }

    @Test
    void succeedsAfterOneRetryableFailure() {
        AtomicInteger calls = new AtomicInteger();
        ReactiveRetryTemplate template = fastTemplate(3);

        Mono<String> source = Mono.defer(() -> {
            int attempt = calls.incrementAndGet();
            if (attempt < 2) {
                return Mono.error(new OptimisticLockingFailureException("conflict"));
            }
            return Mono.just("ok");
        });

        StepVerifier.create(template.execute(source))
                .expectNext("ok")
                .verifyComplete();

        assertEquals(2, calls.get());
    }

    @Test
    void propagatesLastErrorWhenAttemptsExhausted() {
        AtomicInteger calls = new AtomicInteger();
        ReactiveRetryTemplate template = fastTemplate(3);

        Mono<String> source = Mono.defer(() -> {
            calls.incrementAndGet();
            return Mono.error(new OptimisticLockingFailureException("always conflict"));
        });

        StepVerifier.create(template.execute(source))
                .expectErrorSatisfies(error -> {
                    assertTrue(error instanceof OptimisticLockingFailureException,
                            "expected last failure to propagate, got " + error);
                    assertEquals("always conflict", error.getMessage());
                })
                .verify();

        assertEquals(3, calls.get());
    }

    @Test
    void doesNotRetryNonRetryableException() {
        AtomicInteger calls = new AtomicInteger();
        ReactiveRetryTemplate template = fastTemplate(3);

        Mono<String> source = Mono.defer(() -> {
            calls.incrementAndGet();
            return Mono.error(new IOException("fatal"));
        });

        StepVerifier.create(template.execute(source))
                .expectError(IOException.class)
                .verify();

        assertEquals(1, calls.get());
    }

    @Test
    void executeFluxRetriesOnRetryableError() {
        AtomicInteger calls = new AtomicInteger();
        ReactiveRetryTemplate template = fastTemplate(3);

        Flux<Integer> source = Flux.defer(() -> {
            int attempt = calls.incrementAndGet();
            if (attempt < 2) {
                return Flux.<Integer>error(new OptimisticLockingFailureException("conflict"));
            }
            return Flux.just(1, 2, 3);
        });

        StepVerifier.create(template.execute(source))
                .expectNext(1, 2, 3)
                .verifyComplete();

        assertEquals(2, calls.get());
    }

    @Test
    void maxAttemptsOneRunsExactlyOnceWithoutRetry() {
        AtomicInteger calls = new AtomicInteger();
        ReactiveRetryTemplate template = ReactiveRetryTemplate.builder()
                .maxAttempts(1)
                .initialBackoff(Duration.ofMillis(1))
                .backoffMultiplier(1.0)
                .maxBackoff(Duration.ofMillis(1))
                .retryable(OptimisticLockingFailureException.class::isInstance)
                .build();

        Mono<String> source = Mono.defer(() -> {
            calls.incrementAndGet();
            return Mono.error(new OptimisticLockingFailureException("conflict"));
        });

        StepVerifier.create(template.execute(source))
                .expectError(OptimisticLockingFailureException.class)
                .verify();

        assertEquals(1, calls.get());
    }

    @Test
    void backoffDelaysScheduleOnVirtualClock() {
        AtomicInteger calls = new AtomicInteger();
        ReactiveRetryTemplate template = ReactiveRetryTemplate.builder()
                .maxAttempts(3)
                .initialBackoff(Duration.ofSeconds(1))
                .backoffMultiplier(2.0)
                .maxBackoff(Duration.ofSeconds(10))
                .retryable(OptimisticLockingFailureException.class::isInstance)
                .build();

        StepVerifier.withVirtualTime(() -> {
            Mono<String> source = Mono.defer(() -> {
                int attempt = calls.incrementAndGet();
                if (attempt < 3) {
                    return Mono.error(new OptimisticLockingFailureException("conflict"));
                }
                return Mono.just("ok");
            });
            return template.execute(source);
        })
                .expectSubscription()
                // 첫 retry는 1s + jitter(up to 50%) → 가장 보수적인 상한 1.5s 까지 가상 시간 전진
                .expectNoEvent(Duration.ofMillis(500))
                .thenAwait(Duration.ofSeconds(2))
                // 두 번째 retry는 2s + jitter, 안전하게 충분히 전진
                .thenAwait(Duration.ofSeconds(3))
                .expectNext("ok")
                .verifyComplete();

        assertEquals(3, calls.get());
    }

    @Test
    void optimisticLockRetryFactoryDefaults() {
        ReactiveRetryTemplate template = ReactiveRetryTemplate.optimisticLockRetry();

        assertEquals(3, template.maxAttempts());
        assertEquals(Duration.ofMillis(10), template.initialBackoff());
        assertEquals(2.0, template.backoffMultiplier());
        assertEquals(Duration.ofMillis(200), template.maxBackoff());
        assertTrue(template.retryable().test(new OptimisticLockingFailureException("x")));
        assertTrue(!template.retryable().test(new IOException("x")));
    }

    @Test
    void builderRejectsMaxAttemptsLessThanOne() {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> ReactiveRetryTemplate.builder()
                        .maxAttempts(0)
                        .initialBackoff(Duration.ofMillis(1))
                        .backoffMultiplier(1.0)
                        .maxBackoff(Duration.ofMillis(1))
                        .retryable(t -> true)
                        .build());
        assertTrue(ex.getMessage().contains("maxAttempts"));
    }

    @Test
    void builderRejectsMultiplierLessThanOne() {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> ReactiveRetryTemplate.builder()
                        .maxAttempts(3)
                        .initialBackoff(Duration.ofMillis(1))
                        .backoffMultiplier(0.5)
                        .maxBackoff(Duration.ofMillis(10))
                        .retryable(t -> true)
                        .build());
        assertTrue(ex.getMessage().contains("backoffMultiplier"));
    }

    @Test
    void builderRejectsMaxBackoffLessThanInitial() {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> ReactiveRetryTemplate.builder()
                        .maxAttempts(3)
                        .initialBackoff(Duration.ofMillis(100))
                        .backoffMultiplier(2.0)
                        .maxBackoff(Duration.ofMillis(10))
                        .retryable(t -> true)
                        .build());
        assertTrue(ex.getMessage().contains("maxBackoff"));
    }

    @Test
    void builderRejectsNegativeInitialBackoff() {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> ReactiveRetryTemplate.builder()
                        .maxAttempts(3)
                        .initialBackoff(Duration.ofMillis(-1))
                        .backoffMultiplier(2.0)
                        .maxBackoff(Duration.ofMillis(10))
                        .retryable(t -> true)
                        .build());
        assertTrue(ex.getMessage().contains("initialBackoff"));
    }
}
