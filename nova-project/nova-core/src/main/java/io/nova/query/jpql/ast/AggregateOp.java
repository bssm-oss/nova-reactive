package io.nova.query.jpql.ast;

/** JPQL 집계 함수. SQL 렌더링 시 소문자 함수명으로 매핑된다. */
public enum AggregateOp {
    COUNT, SUM, AVG, MIN, MAX
}
