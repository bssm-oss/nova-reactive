package io.nova.query;

/**
 * SELECT 결과 행에 대해 데이터베이스에 요청하는 pessimistic lock 강도를 나타낸다.
 *
 * <p>{@link #NONE}은 lock 절을 생성하지 않으며 기본값이다. {@link #FOR_UPDATE}는 표준 SQL
 * {@code FOR UPDATE} 절을, {@link #FOR_SHARE}는 {@code FOR SHARE} 절을 SQL 끝에 덧붙여
 * 다른 트랜잭션의 동시 수정 또는 배타적 점유를 차단한다. 실제 잠금 의미는 dialect 및
 * 트랜잭션 격리 수준에 따라 다르며, lock 절은 명시적으로 행을 fetch하는 SELECT 에만 의미가 있다.
 */
public enum LockMode {
    NONE,
    FOR_UPDATE,
    FOR_SHARE
}
