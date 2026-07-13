package io.nova.query.jpql.ast;

/** 이항 산술 연산자. */
public enum ArithmeticOp {
    ADD("+"), SUBTRACT("-"), MULTIPLY("*"), DIVIDE("/");

    private final String symbol;

    ArithmeticOp(String symbol) {
        this.symbol = symbol;
    }

    public String symbol() {
        return symbol;
    }
}
