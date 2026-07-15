package io.nova.query.jpql.ast;

import java.util.List;

/**
 * JPQL 스칼라 식(select 항목, where 피연산자, order by 키 등)의 sealed AST 노드.
 */
public sealed interface Expression
        permits Expression.Path, Expression.Literal, Expression.NamedParameter, Expression.PositionalParameter,
        Expression.FunctionCall, Expression.Aggregate, Expression.Arithmetic, Expression.Case,
        Expression.ScalarSubquery, Expression.Cast, Expression.Type, Expression.EntityTypeLiteral,
        Expression.Treat, Expression.QuantifiedSubquery, Expression.Extract, Expression.Trim {

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

    /**
     * {@code CAST(value AS type)} 형변환식. {@code targetType}은 파서가 대문자로 정규화한 JPQL 타입 토큰
     * (예 {@code STRING}, {@code INTEGER})이며, SQL 렌더 단계에서 안전한 표준 SQL 타입으로 화이트리스트
     * 매핑된다(임의 문자열이 SQL에 삽입되지 않는다).
     */
    record Cast(Expression value, String targetType) implements Expression {
    }

    /**
     * {@code TYPE(alias)} 다형성 식 — 상속 계층 엔티티의 discriminator 컬럼 값을 나타낸다. 비교/IN 술어의
     * 좌변으로 쓰여 {@code TYPE(e) = Sub} / {@code TYPE(e) IN (A, B)}가 discriminator 조건으로 렌더된다.
     */
    record Type(String alias) implements Expression {
    }

    /**
     * {@code TYPE(alias) = EntityName}에서 우변에 오는 엔티티 타입 리터럴. SQL 렌더 단계에서 해당 엔티티의
     * discriminator 값으로 바인딩된다(임의 문자열이 SQL에 삽입되지 않는다).
     */
    record EntityTypeLiteral(String entityName) implements Expression {
    }

    /**
     * {@code TREAT(alias AS Subtype).segments} downcast 경로 식. {@code segments}가 비면 downcast된 엔티티
     * 자체를 가리키고(스칼라 투영에서는 fail-fast), 하나 이상이면 서브타입의 속성 컬럼으로 해석된다. SINGLE_TABLE
     * 상속에서 downcast는 discriminator 제한을 함께 적용한다.
     */
    record Treat(String alias, String subtype, List<String> segments) implements Expression {
        public Treat {
            segments = List.copyOf(segments);
        }
    }

    /** {@code = ANY (subquery)} / {@code > ALL (subquery)} 등 정량 서브쿼리 비교의 우변. {@code SOME}은 파서가 {@code ANY}로 정규화한다. */
    record QuantifiedSubquery(Quantifier quantifier, Subquery subquery) implements Expression {
    }

    /** 정량 비교 한정사. JPQL {@code SOME}은 {@code ANY}와 동치이므로 파서 단계에서 {@link #ANY}로 정규화한다. */
    enum Quantifier {
        ANY, ALL
    }

    /**
     * {@code EXTRACT(field FROM source)} — 날짜/시간 필드 추출식. {@code field}는 파서가 대문자로 정규화한 표준
     * 필드 토큰(예 {@code YEAR}, {@code MONTH})이며, SQL 렌더 단계에서 화이트리스트로 검증돼 그대로 방출된다
     * (임의 문자열이 SQL에 삽입되지 않는다).
     */
    record Extract(String field, Expression source) implements Expression {
    }

    /**
     * {@code TRIM([LEADING|TRAILING|BOTH] [trimChar] FROM value)} 문자열 트림식. {@code spec}은 파서가 대문자로
     * 정규화한 위치 한정사(생략 시 {@code BOTH})이며 SQL 렌더 단계에서 화이트리스트 키워드로만 방출된다.
     * {@code trimChar}가 {@code null}이면 기본 공백 트림이고, 셋 다 기본이면 평범한 {@code trim(value)}로 렌더된다.
     */
    record Trim(String spec, Expression trimChar, Expression value) implements Expression {
    }
}
