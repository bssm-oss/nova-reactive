package io.nova.exception;

/**
 * {@link io.nova.annotation.Version}으로 보호된 행에 대해 update 또는 delete의 affected rows가 0이라
 * 다른 트랜잭션이 같은 행을 먼저 변경했을 때 발행된다.
 */
public class OptimisticLockingFailureException extends RuntimeException {
    public OptimisticLockingFailureException(String message) {
        super(message);
    }
}
