package io.nova.core;

import io.nova.query.QuerySpec;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.function.Function;

/**
 * 엔티티 저장, 조회, 삭제를 위한 기본 reactive 진입점이다.
 */
public interface ReactiveEntityOperations {
    /**
     * 식별자 상태를 기준으로 insert 또는 update를 선택해 엔티티를 저장한다.
     */
    <T> Mono<T> save(T entity);

    /**
     * 식별자로 단건 엔티티를 조회한다.
     */
    <T, ID> Mono<T> findById(Class<T> entityType, ID id);

    /**
     * 주어진 쿼리 명세에 맞는 엔티티를 모두 조회한다.
     */
    <T> Flux<T> findAll(Class<T> entityType, QuerySpec querySpec);

    /**
     * 엔티티가 가진 식별자 값을 사용해 삭제한다.
     */
    <T> Mono<Long> delete(T entity);

    /**
     * 식별자로 직접 엔티티를 삭제한다.
     */
    <T, ID> Mono<Long> deleteById(Class<T> entityType, ID id);

    /**
     * 주어진 쿼리 명세에 맞는 행 수를 반환한다.
     */
    <T> Mono<Long> count(Class<T> entityType, QuerySpec querySpec);

    /**
     * 주어진 쿼리 명세에 맞는 행이 하나 이상 존재하는지 반환한다.
     */
    <T> Mono<Boolean> exists(Class<T> entityType, QuerySpec querySpec);

    /**
     * 설정된 transaction operations를 사용해 콜백을 트랜잭션 경계 안에서 실행한다.
     */
    <R> Mono<R> inTransaction(Function<ReactiveEntityOperations, Mono<R>> callback);

    /**
     * 여러 엔티티를 한 번에 저장한다. 기본 구현은 단건 {@link #save(Object)}로 폴백한다.
     * 같은 SQL 셰이프끼리 묶어 배치로 실행하는 최적화는 구현체에서 override한다.
     */
    default <T> Flux<T> saveAll(Iterable<T> entities) {
        return Flux.fromIterable(entities).concatMap(this::save);
    }

    /**
     * 여러 엔티티를 한 번에 삭제한다. 기본 구현은 단건 {@link #delete(Object)}로 폴백한다.
     * 반환값은 영향 받은 총 행 수의 합이다.
     */
    default <T> Mono<Long> deleteAll(Iterable<T> entities) {
        return Flux.fromIterable(entities).concatMap(this::delete).reduce(0L, Long::sum);
    }

    /**
     * 여러 식별자에 해당하는 엔티티를 한 번에 삭제한다. 기본 구현은 단건 {@link #deleteById(Class, Object)}로 폴백한다.
     * 반환값은 영향 받은 총 행 수의 합이다.
     */
    default <T, ID> Mono<Long> deleteAllById(Class<T> entityType, Iterable<ID> ids) {
        return Flux.fromIterable(ids).concatMap(id -> deleteById(entityType, id)).reduce(0L, Long::sum);
    }
}
