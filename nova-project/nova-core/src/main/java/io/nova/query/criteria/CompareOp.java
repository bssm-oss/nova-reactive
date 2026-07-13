package io.nova.query.criteria;

/**
 * Criteria 스칼라 비교 연산자. 스칼라 SQL 렌더 시 {@link #symbol()}로, 엔티티 QuerySpec 변환 시
 * 대응하는 {@code io.nova.query.Criteria} 팩토리로 매핑된다.
 */
public enum CompareOp {
    EQ("="),
    NE("<>"),
    GT(">"),
    GE(">="),
    LT("<"),
    LE("<=");

    private final String symbol;

    CompareOp(String symbol) {
        this.symbol = symbol;
    }

    public String symbol() {
        return symbol;
    }
}
