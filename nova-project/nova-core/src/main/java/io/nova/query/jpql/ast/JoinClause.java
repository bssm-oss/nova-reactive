package io.nova.query.jpql.ast;

/**
 * {@code [INNER|LEFT] JOIN owner.relation alias} 조인 절. {@code fetch}는 JOIN FETCH 여부이며 v1에서는
 * 파서가 이 플래그가 켜진 조인을 fail-fast로 거부한다(Wave2 W3 담당).
 *
 * @param inner       true면 INNER JOIN, false면 LEFT (OUTER) JOIN
 * @param fetch       JOIN FETCH 여부(파서가 인지만; v1 미지원)
 * @param ownerAlias  조인 경로의 소유자 별칭(예 {@code o.employee}의 {@code o})
 * @param relation    조인할 연관 필드명(예 {@code employee})
 * @param alias       조인 결과에 부여한 별칭
 */
public record JoinClause(boolean inner, boolean fetch, String ownerAlias, String relation, String alias) {
}
