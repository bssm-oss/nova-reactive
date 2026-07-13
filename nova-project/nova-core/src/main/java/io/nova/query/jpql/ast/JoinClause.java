package io.nova.query.jpql.ast;

/**
 * {@code [INNER|LEFT] JOIN [FETCH] owner.relation [alias]} 조인 절. {@code fetch}가 true면 {@code JOIN FETCH}로,
 * 엔티티 반환 SELECT에서 해당 연관을 결과 hydration에 반영한다(별칭은 선택). {@code fetch}가 false인 일반
 * 조인은 스칼라/집계 경로에서만 쓰이며 별칭이 필수다.
 *
 * @param inner       true면 INNER JOIN, false면 LEFT (OUTER) JOIN
 * @param fetch       JOIN FETCH 여부
 * @param ownerAlias  조인 경로의 소유자 별칭(예 {@code o.employee}의 {@code o})
 * @param relation    조인할 연관 필드명(예 {@code employee})
 * @param alias       조인 결과에 부여한 별칭(JOIN FETCH는 {@code null} 가능)
 */
public record JoinClause(boolean inner, boolean fetch, String ownerAlias, String relation, String alias) {
}
