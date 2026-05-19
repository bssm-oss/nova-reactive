package io.nova.metrics.micrometer;

import io.micrometer.core.instrument.MeterRegistry;
import io.nova.core.SqlExecutionListener;
import io.nova.sql.SqlStatement;

import java.time.Duration;
import java.util.Objects;

/**
 * Micrometer 기반 {@link SqlExecutionListener} 어댑터.
 * <p>
 * 성공 / 실패 실행마다 {@code "<prefix>.query"} timer를 {@code outcome} tag와 함께 기록한다.
 * 실패 시에는 {@code "<prefix>.errors"} counter도 함께 증가시킨다.
 * <p>
 * Micrometer의 {@link MeterRegistry#timer(String, String...)} /
 * {@link MeterRegistry#counter(String, String...)} 호출은 동일 name + tag 조합에 대해 idempotent
 * 하므로 별도 캐싱 없이 매 hook에서 직접 호출한다.
 */
public final class MicrometerSqlExecutionListener implements SqlExecutionListener {

    /**
     * 명시적인 prefix가 지정되지 않은 경우 사용되는 기본 metric prefix.
     */
    public static final String DEFAULT_METRIC_PREFIX = "nova.sql";

    private final MeterRegistry registry;
    private final String timerName;
    private final String errorCounterName;

    public MicrometerSqlExecutionListener(MeterRegistry registry) {
        this(registry, DEFAULT_METRIC_PREFIX);
    }

    public MicrometerSqlExecutionListener(MeterRegistry registry, String metricPrefix) {
        this.registry = Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(metricPrefix, "metricPrefix");
        if (metricPrefix.isBlank()) {
            throw new IllegalArgumentException("metricPrefix must not be blank");
        }
        this.timerName = metricPrefix + ".query";
        this.errorCounterName = metricPrefix + ".errors";
    }

    @Override
    public void onBeforeExecution(SqlStatement statement) {
        // intentional no-op: timing은 onAfter/onError에 전달되는 Duration으로 처리된다.
    }

    @Override
    public void onAfterExecution(SqlStatement statement, Duration elapsed, long affectedRows) {
        registry.timer(timerName, "outcome", "success").record(elapsed);
    }

    @Override
    public void onError(SqlStatement statement, Duration elapsed, Throwable error) {
        String exceptionTag = error == null ? "none" : error.getClass().getSimpleName();
        registry.timer(timerName, "outcome", "error", "exception", exceptionTag).record(elapsed);
        registry.counter(errorCounterName, "exception", exceptionTag).increment();
    }
}
