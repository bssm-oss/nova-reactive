package io.nova.tx;

/**
 * Reactive 트랜잭션이 활성 트랜잭션과 어떻게 상호작용할지를 결정하는 propagation 정책이다.
 */
public enum Propagation {
    /** 활성 트랜잭션이 있으면 합류하고, 없으면 새 트랜잭션을 시작한다. */
    REQUIRED,
    /** 항상 새 트랜잭션을 시작한다. 활성 트랜잭션이 있으면 콜백 동안 suspend한다. */
    REQUIRES_NEW,
    /** 활성 트랜잭션 안에서 SAVEPOINT를 시작한다. 부모 트랜잭션이 없으면 새로 시작한다. */
    NESTED,
    /** 활성 트랜잭션을 요구한다. 없으면 {@link IllegalStateException}을 발생시킨다. */
    MANDATORY,
    /** 활성 트랜잭션이 있으면 합류하고, 없으면 트랜잭션 없이 그대로 실행한다. */
    SUPPORTS,
    /** 활성 트랜잭션이 있으면 suspend하고, 새 트랜잭션 없이 그대로 실행한다. */
    NOT_SUPPORTED,
    /** 활성 트랜잭션이 있으면 {@link IllegalStateException}을 발생시킨다. */
    NEVER
}
