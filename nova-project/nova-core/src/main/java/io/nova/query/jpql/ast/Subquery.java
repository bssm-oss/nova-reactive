package io.nova.query.jpql.ast;

import java.util.List;

/**
 * WHERE/HAVING 안에 중첩되는 서브쿼리. 최상위 SELECT와 달리 페이징/정렬은 없고 단일 결과 식을 갖는다
 * (EXISTS는 {@code selection}을 상수 1로 렌더한다). {@code correlation}은 바깥 쿼리 별칭을 그대로 참조한다.
 *
 * @param selection    서브쿼리가 노출하는 단일 식(EXISTS에서는 무시). null이면 {@code SELECT 1} 취급.
 * @param rootEntity   FROM 엔티티명
 * @param rootAlias    FROM 별칭
 * @param joins        조인 절
 * @param where        WHERE(없으면 null)
 * @param groupBy      GROUP BY 경로들
 * @param having       HAVING(없으면 null)
 */
public record Subquery(
        Expression selection,
        String rootEntity,
        String rootAlias,
        List<JoinClause> joins,
        Predicate where,
        List<Expression.Path> groupBy,
        Predicate having) {
    public Subquery {
        joins = List.copyOf(joins);
        groupBy = List.copyOf(groupBy);
    }
}
