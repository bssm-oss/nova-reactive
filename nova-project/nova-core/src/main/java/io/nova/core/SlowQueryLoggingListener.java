package io.nova.core;

import io.nova.sql.SqlStatement;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Threshold 이상으로 오래 걸린 SQL 실행을 로깅하는 {@link SqlExecutionListener} 구현.
 * <p>
 * 임계치 이상으로 elapsed time이 소요된 {@code onAfterExecution} 호출에 대해 한 줄을 logger에 emit 한다.
 * Error 경로({@code onError}) 또한 elapsed time과 함께 보고된다. 두 경로 모두 PII 보호를 위해
 * {@link SqlStatement#bindings()}는 로그 메시지에 포함하지 않는다.
 * <p>
 * 기본 logger는 {@code System.err::println}이며, 테스트나 SLF4J 등 외부 logger와 연동하려면
 * {@link Consumer} 형태로 주입할 수 있다.
 * <p>
 * {@code threshold}는 {@link Duration#ZERO} 이상이어야 하며, {@code ZERO}는 "모든 쿼리 로깅"으로
 * 합리적으로 동작한다.
 */
public final class SlowQueryLoggingListener implements SqlExecutionListener {
    private final Duration threshold;
    private final Consumer<String> logger;

    public SlowQueryLoggingListener(Duration threshold) {
        this(threshold, System.err::println);
    }

    public SlowQueryLoggingListener(Duration threshold, Consumer<String> logger) {
        Objects.requireNonNull(threshold, "threshold");
        Objects.requireNonNull(logger, "logger");
        if (threshold.isNegative()) {
            throw new IllegalArgumentException("threshold must not be negative: " + threshold);
        }
        this.threshold = threshold;
        this.logger = logger;
    }

    /**
     * Logging 임계치.
     */
    public Duration threshold() {
        return threshold;
    }

    @Override
    public void onAfterExecution(SqlStatement statement, Duration elapsed, long affectedRows) {
        if (elapsed.compareTo(threshold) >= 0) {
            logger.accept(String.format(
                    "[slow] %d ms - affected=%d - %s",
                    elapsed.toMillis(), affectedRows, statement.sql()));
        }
    }

    @Override
    public void onError(SqlStatement statement, Duration elapsed, Throwable error) {
        logger.accept(String.format(
                "[slow:error] %d ms - %s - %s",
                elapsed.toMillis(), error.getClass().getSimpleName(), statement.sql()));
    }
}
