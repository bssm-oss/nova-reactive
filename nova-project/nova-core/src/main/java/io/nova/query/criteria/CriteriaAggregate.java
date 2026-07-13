package io.nova.query.criteria;

/**
 * {@code CriteriaBuilder.count/countDistinct/sum/avg/max/min}이 만드는 집계 표현식. 피연산자는 단일
 * 컬럼 경로({@link CriteriaColumnPath})여야 하며, 스칼라 SQL 렌더 시 {@code fn("col")} 형태가 된다.
 * 집계 표현식이 SELECT/HAVING에 하나라도 등장하면 쿼리는 스칼라 실행 경로로 라우팅된다.
 *
 * @param <N> 집계 결과 타입(count=Long, avg=Double, sum/max/min=피연산자 타입)
 */
final class CriteriaAggregate<N> extends AbstractCriteriaExpression<N> {

    private final AggregateFunction function;
    private final CriteriaColumnPath operand;

    CriteriaAggregate(AggregateFunction function, CriteriaColumnPath operand, Class<N> resultType) {
        super(resultType);
        this.function = function;
        this.operand = operand;
    }

    AggregateFunction function() {
        return function;
    }

    CriteriaColumnPath operand() {
        return operand;
    }
}
