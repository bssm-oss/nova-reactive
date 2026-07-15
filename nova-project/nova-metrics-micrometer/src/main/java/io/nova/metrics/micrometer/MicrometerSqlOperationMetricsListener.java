package io.nova.metrics.micrometer;

import io.micrometer.core.instrument.MeterRegistry;
import io.nova.core.SqlExecutionListener;
import io.nova.sql.SqlStatement;

import java.time.Duration;
import java.util.Objects;

/**
 * Micrometer 기반 operation 단위 SQL metrics {@link SqlExecutionListener} 어댑터.
 * <p>
 * {@link MicrometerSqlExecutionListener}가 노출하는 {@code "<prefix>.query"} timer /
 * {@code "<prefix>.errors"} counter는 operation 종류와 무관하게 전체 SQL 실행을 하나로 집계한다.
 * 이 리스너는 그 위에 operation({@code save}/{@code find}/{@code delete}/{@code query}) 단위로
 * 세분화된 3개의 메트릭을 추가로 노출한다:
 * <ul>
 *   <li>{@code "<prefix>.operation.duration"} timer — {@code operation}, {@code outcome} tag로
 *       구분되는 operation별 latency.</li>
 *   <li>{@code "<prefix>.operation.count"} counter — {@code operation} tag로 구분되는 실행 횟수
 *       (성공/실패 무관 총량).</li>
 *   <li>{@code "<prefix>.operation.slow"} counter — {@code operation}, {@code outcome} tag로
 *       구분되는, {@code slowQueryThreshold} 이상 걸린 실행 횟수.</li>
 * </ul>
 * Operation 분류는 {@link SqlOperationClassifier}를 통해 SQL 텍스트의 선행 키워드로 근사한다 —
 * {@link SqlExecutionListener} 계약에는 호출자가 어떤 {@code ReactiveEntityOperations} 메서드를
 * 거쳤는지 담겨 있지 않기 때문이다.
 * <p>
 * {@code slowQueryThreshold}는 {@link io.nova.core.SlowQueryLoggingListener}와 동일하게 명시적으로
 * 주입해야 하며 별도 기본값을 가정하지 않는다 — 임계치는 애플리케이션의 SLO에 따라 달라지기 때문이다.
 * {@link Duration#ZERO}는 "모든 실행을 slow로 카운트"로 합리적으로 동작한다.
 */
public final class MicrometerSqlOperationMetricsListener implements SqlExecutionListener {

    /**
     * 명시적인 prefix가 지정되지 않은 경우 사용되는 기본 metric prefix.
     */
    public static final String DEFAULT_METRIC_PREFIX = "nova.sql";

    private final MeterRegistry registry;
    private final Duration slowQueryThreshold;
    private final String operationTimerName;
    private final String operationCounterName;
    private final String slowCounterName;

    public MicrometerSqlOperationMetricsListener(MeterRegistry registry, Duration slowQueryThreshold) {
        this(registry, DEFAULT_METRIC_PREFIX, slowQueryThreshold);
    }

    public MicrometerSqlOperationMetricsListener(
            MeterRegistry registry, String metricPrefix, Duration slowQueryThreshold) {
        this.registry = Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(metricPrefix, "metricPrefix");
        if (metricPrefix.isBlank()) {
            throw new IllegalArgumentException("metricPrefix must not be blank");
        }
        this.slowQueryThreshold = Objects.requireNonNull(slowQueryThreshold, "slowQueryThreshold");
        if (slowQueryThreshold.isNegative()) {
            throw new IllegalArgumentException("slowQueryThreshold must not be negative: " + slowQueryThreshold);
        }
        this.operationTimerName = metricPrefix + ".operation.duration";
        this.operationCounterName = metricPrefix + ".operation.count";
        this.slowCounterName = metricPrefix + ".operation.slow";
    }

    /**
     * Slow-query 카운터 임계치.
     */
    public Duration slowQueryThreshold() {
        return slowQueryThreshold;
    }

    @Override
    public void onBeforeExecution(SqlStatement statement) {
        // intentional no-op: 모든 메트릭은 elapsed time이 확정되는 onAfter/onError에서 기록된다.
    }

    @Override
    public void onAfterExecution(SqlStatement statement, Duration elapsed, long affectedRows) {
        record(statement, elapsed, "success");
    }

    @Override
    public void onError(SqlStatement statement, Duration elapsed, Throwable error) {
        Objects.requireNonNull(error, "error");
        record(statement, elapsed, "error");
    }

    private void record(SqlStatement statement, Duration elapsed, String outcome) {
        String operation = SqlOperationClassifier.classify(statement.sql());

        registry.timer(operationTimerName, "operation", operation, "outcome", outcome).record(elapsed);
        registry.counter(operationCounterName, "operation", operation).increment();

        if (elapsed.compareTo(slowQueryThreshold) >= 0) {
            registry.counter(slowCounterName, "operation", operation, "outcome", outcome).increment();
        }
    }
}
