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
 * <p>엔티티 자체를 반환하는 단순 SELECT(단일 루트, 집계/GROUP BY 없음)는 Nova {@code QuerySpec}으로
 * 변환해 기존 엔티티 하이드레이션 경로를 재사용하고, 스칼라/집계 SELECT는 이 패키지의 자체
 * {@code CriteriaSqlBuilder}가 dialect SQL로 렌더해 {@code queryNative}로 실행한다. JPA 인터페이스의
 * 미지원 구성(join/fetch/subquery/함수/산술 등)은 조용히 무시하지 않고
 * {@link io.nova.query.criteria.CriteriaException}으로 fail-fast한다.
 */
package io.nova.query.criteria;
