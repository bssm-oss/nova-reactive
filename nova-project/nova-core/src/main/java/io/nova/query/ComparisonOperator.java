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
    IS_NOT_NULL("is not null"),
    IN("in"),
    NOT_IN("not in"),
    BETWEEN("between"),
    NOT_LIKE("not like"),
    ILIKE("ilike"),
    NOT_ILIKE("not ilike");

    private final String sql;

    ComparisonOperator(String sql) {
        this.sql = sql;
    }

    public String sql() {
        return sql;
    }
}
