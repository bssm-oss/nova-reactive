package io.nova.query.jpql.ast;

import java.util.List;

/**
 * JPQL WHERE/HAVING/CASE 조건의 sealed AST 노드.
 */
public sealed interface Predicate
        permits Predicate.Comparison, Predicate.And, Predicate.Or, Predicate.Not,
        Predicate.Like, Predicate.Between, Predicate.Null, Predicate.InList, Predicate.InSubquery,
        Predicate.Exists {

    /** {@code left op right} 비교. */
    record Comparison(ComparisonOp op, Expression left, Expression right) implements Predicate {
    }

    /** {@code a AND b} (여러 항을 왼쪽 결합으로 접음). */
    record And(Predicate left, Predicate right) implements Predicate {
    }

    /** {@code a OR b}. */
    record Or(Predicate left, Predicate right) implements Predicate {
    }

    /** {@code NOT a}. */
    record Not(Predicate inner) implements Predicate {
    }

    /**
     * {@code value [NOT] LIKE pattern [ESCAPE c]}. {@code escape}가 {@code null}이면 ESCAPE 절 없음.
     */
    record Like(Expression value, Expression pattern, Character escape, boolean negated) implements Predicate {
    }

    /** {@code value [NOT] BETWEEN low AND high}. */
    record Between(Expression value, Expression low, Expression high, boolean negated) implements Predicate {
    }

    /** {@code value IS [NOT] NULL}. */
    record Null(Expression value, boolean negated) implements Predicate {
    }

    /** {@code value [NOT] IN (a, b, ...)} — 명시 리스트. */
    record InList(Expression value, List<Expression> items, boolean negated) implements Predicate {
        public InList {
            items = List.copyOf(items);
        }
    }

    /** {@code value [NOT] IN (subquery)}. */
    record InSubquery(Expression value, Subquery subquery, boolean negated) implements Predicate {
    }

    /** {@code [NOT] EXISTS (subquery)}. */
    record Exists(Subquery subquery, boolean negated) implements Predicate {
    }
}
