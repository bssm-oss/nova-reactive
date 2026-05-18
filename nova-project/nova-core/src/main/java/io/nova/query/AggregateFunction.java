package io.nova.query;

/**
 * SQL 집계 함수의 종류를 식별한다. {@link Aggregation}과 {@link AggregateSpec}이 사용한다.
 *
 * <ul>
 *   <li>{@link #COUNT} — {@code count(col)}</li>
 *   <li>{@link #COUNT_DISTINCT} — {@code count(distinct col)}</li>
 *   <li>{@link #SUM} — {@code sum(col)}</li>
 *   <li>{@link #AVG} — {@code avg(col)}</li>
 *   <li>{@link #MIN} — {@code min(col)}</li>
 *   <li>{@link #MAX} — {@code max(col)}</li>
 * </ul>
 */
public enum AggregateFunction {
    COUNT,
    COUNT_DISTINCT,
    SUM,
    AVG,
    MIN,
    MAX
}
