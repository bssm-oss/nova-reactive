package io.nova.core;

import io.nova.sql.SqlStatement;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * 여러 {@link SqlExecutionListener}로 동일 이벤트를 fan-out 한다.
 * <p>
 * 한 listener가 throw해도 나머지 listener는 정상적으로 호출된다 (예외 격리). 전파되는 결과 예외는
 * 가장 먼저 발생한 예외이며, 이후에 발생한 예외는 {@link Throwable#addSuppressed(Throwable)}로 attach된다.
 */
public final class CompositeSqlExecutionListener implements SqlExecutionListener {
    private final List<SqlExecutionListener> delegates;

    public CompositeSqlExecutionListener(List<SqlExecutionListener> delegates) {
        Objects.requireNonNull(delegates, "delegates");
        for (SqlExecutionListener delegate : delegates) {
            Objects.requireNonNull(delegate, "delegate listener must not be null");
        }
        this.delegates = List.copyOf(delegates);
    }

    public CompositeSqlExecutionListener(SqlExecutionListener... delegates) {
        this(List.of(delegates));
    }

    /**
     * Composite가 fan-out 대상으로 보유한 listener를 반환한다.
     */
    public List<SqlExecutionListener> delegates() {
        return delegates;
    }

    @Override
    public void onBeforeExecution(SqlStatement statement) {
        RuntimeException collected = null;
        for (SqlExecutionListener delegate : delegates) {
            try {
                delegate.onBeforeExecution(statement);
            } catch (RuntimeException ex) {
                collected = accumulate(collected, ex);
            }
        }
        if (collected != null) {
            throw collected;
        }
    }

    @Override
    public void onAfterExecution(SqlStatement statement, Duration elapsed, long affectedRows) {
        RuntimeException collected = null;
        for (SqlExecutionListener delegate : delegates) {
            try {
                delegate.onAfterExecution(statement, elapsed, affectedRows);
            } catch (RuntimeException ex) {
                collected = accumulate(collected, ex);
            }
        }
        if (collected != null) {
            throw collected;
        }
    }

    @Override
    public void onError(SqlStatement statement, Duration elapsed, Throwable error) {
        RuntimeException collected = null;
        for (SqlExecutionListener delegate : delegates) {
            try {
                delegate.onError(statement, elapsed, error);
            } catch (RuntimeException ex) {
                collected = accumulate(collected, ex);
            }
        }
        if (collected != null) {
            throw collected;
        }
    }

    private static RuntimeException accumulate(RuntimeException current, RuntimeException next) {
        if (current == null) {
            return next;
        }
        current.addSuppressed(next);
        return current;
    }
}
