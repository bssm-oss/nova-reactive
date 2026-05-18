package io.nova.core;

import io.nova.sql.SqlStatement;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Threshold 이상으로 오래 걸린 SQL 실행을 로깅하는 {@link SqlExecutionListener} 구현.
 * <p>
 * 성공 경로({@code onAfterExecution})는 elapsed time이 {@code threshold} 이상일 때에만 한 줄을
 * logger에 emit 한다. 반면 에러 경로({@code onError})는 클래스 이름의 "Slow"와 무관하게
 * threshold를 무시하고 <strong>항상</strong> 로깅한다 — 실패는 빈도와 무관하게 가시성이 필요하기 때문이다.
 * 두 경로 모두 PII 보호를 위해 {@link SqlStatement#bindings()}는 로그 메시지에 포함하지 않는다.
 * <p>
 * 기본 logger는 {@code System.err::println}이며, 테스트나 SLF4J 등 외부 logger와 연동하려면
 * {@link Consumer} 형태로 주입할 수 있다.
 * <p>
 * {@code threshold}는 {@link Duration#ZERO} 이상이어야 하며, {@code ZERO}는 "모든 성공 쿼리 로깅"으로
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

    /**
     * 에러로 종료된 실행을 로깅한다. {@link #threshold}와 무관하게 <strong>항상</strong> 로깅하는데,
     * 실패한 쿼리는 elapsed time이 짧더라도 진단을 위해 가시화되어야 하기 때문이다. 클래스 이름의
     * "Slow"에 가려져 있는 의도이므로 호출 측이 이 동작에 의존해도 안전하다.
     */
    @Override
    public void onError(SqlStatement statement, Duration elapsed, Throwable error) {
        logger.accept(String.format(
                "[slow:error] %d ms - %s - %s",
                elapsed.toMillis(), error.getClass().getSimpleName(), statement.sql()));
    }
}
