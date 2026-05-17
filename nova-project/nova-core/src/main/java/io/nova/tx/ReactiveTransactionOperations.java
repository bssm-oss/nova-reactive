package io.nova.tx;

import reactor.core.publisher.Mono;

import java.util.function.Function;

/**
 * 작업을 reactive 트랜잭션 경계 안에서 실행하기 위한 최소 계약이다.
 */
public interface ReactiveTransactionOperations {
    <T> Mono<T> inTransaction(Function<TransactionContext, Mono<T>> callback);
}
