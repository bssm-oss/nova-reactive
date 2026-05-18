package io.nova.core;

import io.nova.sql.SqlStatement;

import java.time.Duration;

/**
 * SQL 실행 lifecycle을 관찰하기 위한 listener.
 * <p>
 * 모든 메서드는 기본 no-op이며, 필요한 hook만 override하면 된다. listener 구현체가 throw한 예외는
 * 단일 listener를 직접 전달할 경우 reactive chain으로 전파될 수 있다. 예외 격리가 필요하면
 * {@link CompositeSqlExecutionListener}를 사용해 fan-out 한다.
 * <p>
 * {@code affectedRows} 의미는 호출 경로에 따라 다르다.
 * <ul>
 *   <li>{@code execute} / {@code executeBatch}: driver가 보고한 update row count.</li>
 *   <li>{@code queryOne}: mapping된 row의 수 (0 또는 1).</li>
 *   <li>{@code queryMany}: mapping된 row의 수.</li>
 *   <li>{@code executeAndReturnGeneratedKey}: 회수된 generated key row의 수 (성공 시 1).</li>
 * </ul>
 * Nova는 이 interface만 export하며, slow query logging 등 정책은 사용자가 직접 구현한다.
 */
public interface SqlExecutionListener {
    /**
     * 실행이 driver에 위임되기 직전에 호출된다. Reactor pipeline 기준으로 첫 row를 fetch하기 직전에 호출된다.
     */
    default void onBeforeExecution(SqlStatement statement) {
    }

    /**
     * 실행이 성공적으로 완료된 후 호출된다.
     *
     * @param statement    실행된 statement
     * @param elapsed      {@link #onBeforeExecution(SqlStatement)} 호출 직후부터 측정된 elapsed time
     * @param affectedRows 호출 경로별 의미는 interface javadoc 참고
     */
    default void onAfterExecution(SqlStatement statement, Duration elapsed, long affectedRows) {
    }

    /**
     * 실행 도중 에러가 발생하면 호출된다.
     *
     * @param statement statement
     * @param elapsed   {@link #onBeforeExecution(SqlStatement)} 호출 직후부터 측정된 elapsed time
     * @param error     발생한 에러
     */
    default void onError(SqlStatement statement, Duration elapsed, Throwable error) {
    }

    /**
     * 모든 hook이 no-op인 listener.
     */
    SqlExecutionListener NO_OP = new SqlExecutionListener() {
    };
}
