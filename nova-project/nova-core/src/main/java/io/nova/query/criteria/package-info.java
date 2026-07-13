/**
 * JPA {@code jakarta.persistence.criteria} 타입세이프 Criteria API의 리액티브 등가 구현이다.
 * 완전 격리된 신규 서브시스템으로, {@code io.nova.query.jpql} 패키지나 코어 hub 파일
 * ({@code EntityMetadataFactory}/{@code SimpleReactiveEntityOperations})을 수정하지 않고
 * 오직 {@link io.nova.core.ReactiveEntityOperations}의 public 진입점에 위임한다.
 *
 * <p>사용자는 표준 JPA Criteria 코드를 그대로 작성한다 — {@code CriteriaBuilder}로
 * {@code CriteriaQuery}/{@code Root}/{@code Predicate}/{@code Order}를 조립한 뒤,
 * blocking {@code EntityManager.createQuery(cq).getResultList()} 대신
 * {@link io.nova.query.criteria.ReactiveCriteriaExecutor}의 리액티브 진입점으로 실행한다
 * ({@code Flux}/{@code Mono} 반환, {@code block()} 미노출).
 *
 * <p>join/서브쿼리가 없는 단순 SELECT는 기존 경로를 그대로 탄다 — 엔티티 반환(단일 루트, 집계/GROUP BY
 * 없음)은 Nova {@code QuerySpec}으로 변환해 하이드레이션을 재사용하고, 스칼라/집계는 자체
 * {@code CriteriaSqlBuilder}가 unqualified SQL로 렌더해 {@code queryNative}로 실행한다.
 *
 * <p><b>join / subquery (Batch C)</b>: {@code root.join("assoc"[, JoinType])}({@code @ManyToOne}/owning
 * {@code @OneToOne}/{@code @OneToMany}/inverse {@code @OneToOne}), 다세그먼트 경로
 * ({@code root.get("dept").get("name")}, 묵시적 INNER join), {@code cb.exists(subquery)} /
 * {@code path.in(subquery)} / 스칼라 서브쿼리 비교({@code cb.gt(path, subquery)}), 컬럼 대 컬럼 비교
 * (상관 서브쿼리 correlation)를 지원한다. 이런 쿼리는 {@code AliasedCriteriaSqlBuilder}가 {@code t0}/
 * {@code t1}… alias로 한정한 SQL을 만들며, 스칼라/집계는 {@code queryNative}로, 엔티티 반환은 루트 id를
 * 순서대로 투영해(중복 제거) 기존 하이드레이션에 위임하는 2단계로 실행한다. {@code root.fetch("assoc")}는
 * Nova의 always-eager 모델과 정합해 별도 SQL 없이 수용된다(연관은 하이드레이션 단계에서 항상 로드).
 *
 * <p>여전히 미지원(fail-fast): 다중 루트 카티전, {@code @ManyToMany}/{@code @ElementCollection} join,
 * RIGHT join, 상속/보조 테이블에 대한 join/subquery, 복합키 루트의 join 엔티티 반환, 중첩 서브쿼리,
 * join correlate, 서브쿼리 GROUP BY/HAVING/DISTINCT.
 *
 * <p>v1 caveat: HAVING은 grouping 컬럼 술어만 지원하고 집계 피연산자
 * ({@code having(cb.gt(cb.count(root), n))})는 미지원이며, {@code distinct(true)}는 스칼라/집계 경로에서만
 * 반영되고 엔티티 반환 경로에서는 fail-fast한다(join 엔티티 반환은 id 투영이 이미 중복 제거).
 */
package io.nova.query.criteria;
