package io.nova.query.jpql.ast;

/** ORDER BY 한 항목. {@code ascending=false}면 DESC. */
public record OrderItem(Expression expression, boolean ascending) {
}
