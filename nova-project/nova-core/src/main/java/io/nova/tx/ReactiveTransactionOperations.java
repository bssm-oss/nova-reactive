package io.nova.tx;

import reactor.core.publisher.Mono;

import java.util.function.Function;

/**
 * 작업을 reactive 트랜잭션 경계 안에서 실행하기 위한 최소 계약이다.
 * <p>
 * propagation, isolation, read-only 같은 정책을 명시적으로 지정하려면
 * {@link #inTransaction(TransactionDefinition, Function)}을 사용한다. 단순한
 * {@link #inTransaction(Function)} 호출은 {@link TransactionDefinition#DEFAULT} 정의로
 * 위임된다.
 */
public interface ReactiveTransactionOperations {
    /**
     * {@link TransactionDefinition#DEFAULT} 정의로 콜백을 트랜잭션 경계 안에서 실행한다.
     * 기본 구현은 {@link #inTransaction(TransactionDefinition, Function)}으로 위임한다.
     */
    default <T> Mono<T> inTransaction(Function<TransactionContext, Mono<T>> callback) {
        return inTransaction(TransactionDefinition.DEFAULT, callback);
    }

    /**
     * 주어진 {@link TransactionDefinition}으로 콜백을 실행한다. 구현체는 이 메서드를
     * 반드시 override해야 한다. 기본 구현은 {@link UnsupportedOperationException}을 signal한다.
     */
    default <T> Mono<T> inTransaction(TransactionDefinition definition,
                                      Function<TransactionContext, Mono<T>> callback) {
        return Mono.error(new UnsupportedOperationException(
                "inTransaction(TransactionDefinition, callback) must be overridden by " + getClass().getName()));
    }
}
