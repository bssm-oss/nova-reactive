package io.nova.tx;

import reactor.core.publisher.Mono;

/**
 * 트랜잭션 없이 단일 커넥션을 스코프 동안 공유하기 위한 추상화. {@link ReactiveTransactionOperations}가
 * BEGIN/COMMIT으로 묶는 것과 달리, 이쪽은 커넥션 하나를 스코프 동안 Reactor {@code Context}에 바인딩만
 * 한다(autocommit 유지). 그 안에서 실행되는 read들은 연산당 커넥션을 새로 빌리고 반납하는 비용을 피한다.
 * <p>
 * 구현체(예: {@code R2dbcTransactionManager})는 이미 커넥션이 바인딩돼 있으면(트랜잭션/바깥 read 스코프 안)
 * 새로 따지 않고 그대로 join 해야 한다.
 */
public interface ReactiveConnectionOperations {

    /**
     * {@code work}를 단일 공유 커넥션에 바인딩해 실행한다. 트랜잭션은 시작하지 않으며(autocommit), 스코프 종료
     * 시 커넥션을 반납한다. 이미 커넥션이 바인딩돼 있으면 그대로 재사용한다.
     */
    <T> Mono<T> withConnection(Mono<T> work);
}
