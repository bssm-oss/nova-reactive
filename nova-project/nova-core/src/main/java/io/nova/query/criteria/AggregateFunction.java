package io.nova.query.criteria;

/**
 * Criteria 집계 함수 종류. {@code CriteriaBuilder}의 count/countDistinct/sum/avg/max/min에 대응하며,
 * 스칼라 SQL 렌더 시 {@link #sqlName()}으로 표준 SQL 함수명이 된다.
 */
public enum AggregateFunction {
    COUNT("count", false),
    COUNT_DISTINCT("count", true),
    SUM("sum", false),
    AVG("avg", false),
    MAX("max", false),
    MIN("min", false);

    private final String sqlName;
    private final boolean distinct;

    AggregateFunction(String sqlName, boolean distinct) {
        this.sqlName = sqlName;
        this.distinct = distinct;
    }

    public String sqlName() {
        return sqlName;
    }

    public boolean distinct() {
        return distinct;
    }
}
