package io.nova.query;

public enum ComparisonOperator {
    EQ("="),
    NE("<>"),
    GT(">"),
    GTE(">="),
    LT("<"),
    LTE("<="),
    LIKE("like"),
    IS_NULL("is null"),
    IS_NOT_NULL("is not null");

    private final String sql;

    ComparisonOperator(String sql) {
        this.sql = sql;
    }

    public String sql() {
        return sql;
    }
}
