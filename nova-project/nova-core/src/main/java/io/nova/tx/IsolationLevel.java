package io.nova.tx;

/**
 * 트랜잭션 격리 수준. {@link #DEFAULT}는 underlying driver의 기본값을 그대로 사용한다.
 */
public enum IsolationLevel {
    DEFAULT,
    READ_UNCOMMITTED,
    READ_COMMITTED,
    REPEATABLE_READ,
    SERIALIZABLE
}
