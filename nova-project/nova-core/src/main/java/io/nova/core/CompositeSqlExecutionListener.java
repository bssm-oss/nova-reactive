package io.nova.core;

import io.nova.sql.SqlStatement;

import java.time.Duration;
import java.util.ArrayList;
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
     * Varargs로 받은 listener를 적절히 축약한 {@link SqlExecutionListener}를 반환한다.
     * <p>
     * {@code null} 인자는 거부되며, listener가 0개이면 {@link SqlExecutionListener#NO_OP}을, 1개이면
     * 해당 listener 자체를 그대로 반환한다. 2개 이상일 때만 새 {@link CompositeSqlExecutionListener}로 감싼다.
     *
     * @param listeners 합성할 listener 목록 (null 불가, 각 element도 null 불가)
     * @return 입력 개수에 따라 NO_OP / 단일 listener / Composite
     */
    public static SqlExecutionListener of(SqlExecutionListener... listeners) {
        Objects.requireNonNull(listeners, "listeners");
        if (listeners.length == 0) {
            return SqlExecutionListener.NO_OP;
        }
        if (listeners.length == 1) {
            return Objects.requireNonNull(listeners[0], "listener");
        }
        return new CompositeSqlExecutionListener(List.of(listeners));
    }

    /**
     * Composite가 fan-out 대상으로 보유한 listener를 반환한다.
     */
    public List<SqlExecutionListener> delegates() {
        return delegates;
    }

    /**
     * 기존 delegate 목록 뒤에 {@code listener}를 append한 새 Composite를 반환한다. 본인은 변경되지 않는다.
     *
     * @param listener append할 listener (null 불가)
     * @return 새 {@link CompositeSqlExecutionListener}
     */
    public CompositeSqlExecutionListener add(SqlExecutionListener listener) {
        Objects.requireNonNull(listener, "listener");
        List<SqlExecutionListener> next = new ArrayList<>(delegates.size() + 1);
        next.addAll(delegates);
        next.add(listener);
        return new CompositeSqlExecutionListener(next);
    }

    /**
     * Delegate 중 {@link SqlExecutionListener#NO_OP} 인스턴스를 제거한 새 Composite를 반환한다.
     * 제거 후 listener가 없으면 빈 Composite가 반환된다. 본인은 변경되지 않는다.
     */
    public CompositeSqlExecutionListener withoutNoOp() {
        List<SqlExecutionListener> filtered = new ArrayList<>(delegates.size());
        for (SqlExecutionListener delegate : delegates) {
            if (delegate != SqlExecutionListener.NO_OP) {
                filtered.add(delegate);
            }
        }
        if (filtered.size() == delegates.size()) {
            return this;
        }
        return new CompositeSqlExecutionListener(filtered);
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
