package io.nova.retry;

import io.nova.exception.OptimisticLockingFailureException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * Transient error에 대한 exponential backoff retry를 적용하는 reactive helper다.
 *
 * <p>Reactor의 {@link Retry#backoff(long, Duration)}을 thin wrapping하여,
 * {@link #execute(Mono)} 또는 {@link #execute(Flux)}로 전달된 publisher에
 * 재시도 정책을 일관되게 적용한다. retry 가능한 예외만 backoff 후 재시도하고,
 * 그 외 예외는 즉시 전파한다.
 *
 * <p>인스턴스는 immutable하며 {@link #builder()} 또는
 * {@link #optimisticLockRetry()} 같은 factory를 통해 생성한다.
 *
 * <p><b>Backoff jitter</b>: Reactor {@code Retry.backoff}는 기본 jitter ±50%를 적용한다.
 * 즉 nominal interval이 {@code T}이면 실제 대기 시간은 {@code [T*0.5, T*1.5]} 범위에서
 * 무작위 결정되므로, {@link Builder#maxBackoff(Duration)}로 설정한 상한을 실측 대기가
 * 일시적으로 초과할 수 있다. SLA 또는 timeout 설계 시 이 jitter를 감안하라.
 *
 * <p><b>{@code maxAttempts == 1}</b>: retry 자체를 우회한다. 이 경우
 * {@code initialBackoff}/{@code backoffMultiplier}/{@code maxBackoff} 설정은 모두 무시되며,
 * 호출자는 wrapper를 거치지 않은 것과 동일한 의미를 얻는다.
 *
 * <p><b>{@link #execute(Flux)} 의미론</b>: Reactor의 {@code retryWhen}은 source를 처음부터
 * 재구독한다. 따라서 Flux가 일부 값을 emit한 뒤 retryable 예외로 종료되면 retry 후
 * downstream에 이미 발행된 값이 다시 발행되어 <b>중복 emit</b>가 발생한다. 멱등하지 않은
 * downstream(예: 외부 시스템에 발송하는 sink)은 별도 dedup 또는 idempotency key가 필요하다.
 */
public final class ReactiveRetryTemplate {

    private final int maxAttempts;
    private final Duration initialBackoff;
    private final double backoffMultiplier;
    private final Duration maxBackoff;
    private final Predicate<Throwable> retryable;

    private ReactiveRetryTemplate(
            int maxAttempts,
            Duration initialBackoff,
            double backoffMultiplier,
            Duration maxBackoff,
            Predicate<Throwable> retryable) {
        this.maxAttempts = maxAttempts;
        this.initialBackoff = initialBackoff;
        this.backoffMultiplier = backoffMultiplier;
        this.maxBackoff = maxBackoff;
        this.retryable = retryable;
    }

    /**
     * Optimistic locking 충돌 해소를 위한 기본 정책: 최대 3회 시도, 10ms 초기 대기,
     * exponential factor 2.0, 최대 200ms 대기.
     */
    public static ReactiveRetryTemplate optimisticLockRetry() {
        return builder()
                .maxAttempts(3)
                .initialBackoff(Duration.ofMillis(10))
                .backoffMultiplier(2.0)
                .maxBackoff(Duration.ofMillis(200))
                .retryable(OptimisticLockingFailureException.class::isInstance)
                .build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public int maxAttempts() {
        return maxAttempts;
    }

    public Duration initialBackoff() {
        return initialBackoff;
    }

    public double backoffMultiplier() {
        return backoffMultiplier;
    }

    public Duration maxBackoff() {
        return maxBackoff;
    }

    public Predicate<Throwable> retryable() {
        return retryable;
    }

    /**
     * 주어진 {@link Mono}에 retry 정책을 적용한다. {@code maxAttempts}가 1이면
     * retry 없이 한 번만 실행된다.
     */
    public <T> Mono<T> execute(Mono<T> mono) {
        Objects.requireNonNull(mono, "mono");
        if (maxAttempts <= 1) {
            return mono;
        }
        return mono.retryWhen(buildRetrySpec());
    }

    /**
     * 주어진 {@link Flux}에 retry 정책을 적용한다. {@code maxAttempts}가 1이면
     * retry 없이 한 번만 실행된다.
     */
    public <T> Flux<T> execute(Flux<T> flux) {
        Objects.requireNonNull(flux, "flux");
        if (maxAttempts <= 1) {
            return flux;
        }
        return flux.retryWhen(buildRetrySpec());
    }

    private Retry buildRetrySpec() {
        // Reactor의 maxAttempts는 "retry 횟수"를 의미하므로 총 시도 횟수에서 1을 뺀다.
        long retryCount = (long) maxAttempts - 1L;
        return Retry.backoff(retryCount, initialBackoff)
                .maxBackoff(maxBackoff)
                .multiplier(backoffMultiplier)
                .filter(retryable)
                .onRetryExhaustedThrow((spec, signal) -> signal.failure());
    }

    /**
     * {@link ReactiveRetryTemplate} 빌더. 모든 setter는 새로 build된 인스턴스가
     * immutable임을 보장한다.
     */
    public static final class Builder {

        private int maxAttempts = 1;
        private Duration initialBackoff = Duration.ZERO;
        private double backoffMultiplier = 1.0;
        private Duration maxBackoff = Duration.ZERO;
        private Predicate<Throwable> retryable = t -> true;

        private Builder() {
        }

        public Builder maxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
            return this;
        }

        public Builder initialBackoff(Duration initialBackoff) {
            this.initialBackoff = initialBackoff;
            return this;
        }

        public Builder backoffMultiplier(double backoffMultiplier) {
            this.backoffMultiplier = backoffMultiplier;
            return this;
        }

        public Builder maxBackoff(Duration maxBackoff) {
            this.maxBackoff = maxBackoff;
            return this;
        }

        public Builder retryable(Predicate<Throwable> retryable) {
            this.retryable = retryable;
            return this;
        }

        public ReactiveRetryTemplate build() {
            if (maxAttempts < 1) {
                throw new IllegalArgumentException(
                        "maxAttempts must be >= 1, got " + maxAttempts);
            }
            Objects.requireNonNull(initialBackoff, "initialBackoff");
            if (initialBackoff.isNegative()) {
                throw new IllegalArgumentException(
                        "initialBackoff must be >= Duration.ZERO, got " + initialBackoff);
            }
            if (backoffMultiplier < 1.0) {
                throw new IllegalArgumentException(
                        "backoffMultiplier must be >= 1.0, got " + backoffMultiplier);
            }
            Objects.requireNonNull(maxBackoff, "maxBackoff");
            if (maxBackoff.compareTo(initialBackoff) < 0) {
                throw new IllegalArgumentException(
                        "maxBackoff must be >= initialBackoff, got maxBackoff="
                                + maxBackoff + ", initialBackoff=" + initialBackoff);
            }
            Objects.requireNonNull(retryable, "retryable");
            return new ReactiveRetryTemplate(
                    maxAttempts, initialBackoff, backoffMultiplier, maxBackoff, retryable);
        }
    }
}
