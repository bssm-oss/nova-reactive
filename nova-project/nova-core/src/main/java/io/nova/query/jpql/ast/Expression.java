package io.nova.query.jpql.ast;

import java.util.List;

/**
 * JPQL 스칼라 식(select 항목, where 피연산자, order by 키 등)의 sealed AST 노드.
 */
public sealed interface Expression
        permits Expression.Path, Expression.Literal, Expression.NamedParameter, Expression.PositionalParameter,
        Expression.FunctionCall, Expression.Aggregate, Expression.Arithmetic, Expression.Case,
        Expression.ScalarSubquery {

    /** {@code alias.field.subfield} 경로 식. 첫 세그먼트는 별칭, 나머지는 필드 경로다. */
    record Path(String alias, List<String> segments) implements Expression {
        public Path {
            segments = List.copyOf(segments);
        }
    }

    /** 리터럴 상수. {@code value}는 String/Long/java.math.BigDecimal/Boolean 중 하나. */
    record Literal(Object value) implements Expression {
    }

    /** {@code :name} named 파라미터 참조. */
    record NamedParameter(String name) implements Expression {
    }

    /** {@code ?n} positional 파라미터 참조(1-기반). */
    record PositionalParameter(int position) implements Expression {
    }

    /** {@code LOWER(x)}, {@code CONCAT(a, b)} 같은 스칼라 함수 호출. {@code name}은 uppercase 정규화. */
    record FunctionCall(String name, List<Expression> arguments) implements Expression {
        public FunctionCall {
            arguments = List.copyOf(arguments);
        }
    }

    /** 집계 함수. {@code argument}가 {@code null}이면 {@code COUNT(*)}. */
    record Aggregate(AggregateOp op, boolean distinct, Expression argument) implements Expression {
    }

    /** 이항 산술식 {@code left op right}. */
    record Arithmetic(ArithmeticOp op, Expression left, Expression right) implements Expression {
    }

    /**
     * CASE 식. searched form({@code CASE WHEN cond THEN ... END})만 지원한다. simple form
     * ({@code CASE x WHEN v THEN ...})는 파서가 fail-fast로 거부한다.
     */
    record Case(List<WhenClause> whens, Expression elseResult) implements Expression {
        public Case {
            whens = List.copyOf(whens);
        }
    }

    /** where/having 피연산자로 쓰이는 스칼라 서브쿼리(단일 컬럼 SELECT). */
    record ScalarSubquery(Subquery subquery) implements Expression {
    }
}
