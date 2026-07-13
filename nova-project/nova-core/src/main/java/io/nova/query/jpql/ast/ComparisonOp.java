package io.nova.query.jpql.ast;

/** 비교 연산자. */
public enum ComparisonOp {
    EQ("="), NE("<>"), LT("<"), GT(">"), LE("<="), GE(">=");

    private final String symbol;

    ComparisonOp(String symbol) {
        this.symbol = symbol;
    }

    public String symbol() {
        return symbol;
    }

    public static ComparisonOp fromSymbol(String symbol) {
        for (ComparisonOp op : values()) {
            if (op.symbol.equals(symbol)) {
                return op;
            }
        }
        throw new IllegalArgumentException("Not a comparison operator: " + symbol);
    }
}
