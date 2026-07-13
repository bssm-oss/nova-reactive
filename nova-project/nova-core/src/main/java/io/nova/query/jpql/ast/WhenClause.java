package io.nova.query.jpql.ast;

/** searched CASE의 한 {@code WHEN condition THEN result} 절. */
public record WhenClause(Predicate condition, Expression result) {
}
