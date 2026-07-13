package io.nova.query.jpql.ast;

/** 벌크 UPDATE의 {@code SET path = value} 한 항목. */
public record Assignment(Expression.Path target, Expression value) {
}
