package io.nova.core;

import io.nova.query.Projection;
import io.nova.query.QuerySpec;
import io.nova.query.Updater;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.function.Function;

/**
 * 엔티티 저장, 조회, 삭제를 위한 기본 reactive 진입점이다.
 */
public interface ReactiveEntityOperations {
    /**
     * 식별자 상태를 기준으로 insert 또는 update를 선택해 엔티티를 저장한다.
     * {@code @GeneratedValue}로 생성된 식별자는 저장 후 entity에 다시 주입되어 반환된다.
     */
    <T> Mono<T> save(T entity);

    /**
     * 명시한 property 컬럼만 update한다. {@code save(T)}와 달리 SET 절에 빠진 컬럼은 건드리지 않으므로
     * 다중 사용자가 같은 행의 서로 다른 컬럼을 수정하는 환경에서 lost update를 줄일 수 있다.
     * <p>
     * {@code fields}는 entity의 property name(Java 필드명)이며, 빈 컬렉션·미존재 field·id field는
     * 모두 거부된다. entity의 id가 {@code null}이면 {@code Mono.error(IllegalArgumentException)}이
     * 발행된다. 기본 구현은 외부 구현체가 미구현 시 호출자가 깨지지 않도록 명시적 예외를 던지며,
     * {@link SimpleReactiveEntityOperations}는 {@code SqlRenderer.update(metadata, entity, fields)}로 위임한다.
     */
    default <T> Mono<T> update(T entity, Iterable<String> fields) {
        return Mono.error(new UnsupportedOperationException(
                "ReactiveEntityOperations.update(entity, fields) must be overridden by the implementation"));
    }

    /**
     * 식별자로 단건 엔티티를 조회한다.
     */
    <T, ID> Mono<T> findById(Class<T> entityType, ID id);

    /**
     * 여러 식별자에 해당하는 엔티티를 한 번에 조회한다. 기본 구현은 단건 {@link #findById(Class, Object)}로
     * 폴백하며, 구현체는 {@code IN} 절을 사용한 단일 쿼리로 최적화할 수 있다.
     * <p>
     * 결과 순서는 데이터베이스가 반환하는 순서를 따르므로 {@code ids} 입력 순서와 일치하지 않을 수 있다.
     * 입력 순서가 필요하면 호출자가 결과를 식별자 기준으로 정렬해야 한다. 존재하지 않는 식별자는
     * 결과에 포함되지 않는다.
     */
    default <T, ID> Flux<T> findAllById(Class<T> entityType, Iterable<ID> ids) {
        return Flux.fromIterable(ids).concatMap(id -> findById(entityType, id));
    }

    /**
     * 주어진 쿼리 명세에 맞는 엔티티를 모두 조회한다.
     */
    <T> Flux<T> findAll(Class<T> entityType, QuerySpec querySpec);

    /**
     * 지정된 entity property만 SELECT 절에 포함시켜 가져온 뒤 {@link Projection#projectionType()}으로
     * 매핑한 결과를 발행한다. projection 타입은 record이거나 명시적인 단일 생성자를 가진 일반 class여야
     * 하며, 생성자 파라미터 개수와 순서가 {@link Projection#fields()} 순서와 일치해야 한다.
     * <p>
     * entity property의 column converter는 적용된 뒤 projection 타입에 주입되며, {@code @SoftDelete}
     * 메타데이터가 있는 entity는 SELECT에 자동으로 alive 조건이 덧붙는다. 검증 실패는 모두
     * {@link IllegalArgumentException}으로 발행된다.
     * <p>
     * 기본 구현은 외부 {@link ReactiveEntityOperations} 직접 구현자가 자동으로 깨지지 않도록 명시적
     * 예외를 던지며, {@link SimpleReactiveEntityOperations}는 이 메서드를 override한다.
     */
    default <E, P> Flux<P> findAll(Projection<E, P> projection, QuerySpec querySpec) {
        return Flux.error(new UnsupportedOperationException(
                "ReactiveEntityOperations.findAll(Projection, QuerySpec) must be overridden by the implementation"));
    }

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
     * <p>
     * 주의: 배치 최적화 경로(단일 {@code Statement.add()} 기반)에서는 데이터베이스가 생성한
     * ID를 entity에 다시 set하지 않는다. 생성 ID가 필요한 경우에는 단건 {@link #save(Object)}를
     * 사용하거나 ID를 미리 채워서 전달해야 한다. 기본 구현(단건 fallback)에서는
     * {@link #save(Object)}와 동일하게 생성 ID가 entity에 주입된다.
     * <p>
     * 입력 Iterable이 List가 아닌 경우 내부에서 한 번 수집하므로, 호출 이후 같은 인스턴스를
     * 다시 사용하려는 경우에는 List로 미리 만들어 전달하는 것을 권장한다. 또한 입력 List를
     * 호출자가 동시에 수정하지 않을 책임이 있다 (방어 카피를 수행하지 않는다).
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

    /**
     * predicate에 일치하는 모든 행을 한 번의 {@code delete from t where ...} 쿼리로 삭제한다.
     * {@code querySpec.predicate()}는 non-null이어야 하며, sort/pageable이 함께 들어오면 거부된다 —
     * DELETE 표준에 sort/limit이 없어 dialect별 동작이 달라지기 때문이다. 반환값은 영향 받은 행 수다.
     * <p>
     * 기본 구현은 구현체가 이 메서드를 미구현 시 외부 호출자가 깨지지 않도록 명시적 예외를 던지며,
     * {@link SimpleReactiveEntityOperations}는 {@code SqlRenderer.deleteByQuery}로 위임한다.
     */
    default <T> Mono<Long> deleteAll(Class<T> entityType, QuerySpec querySpec) {
        return Mono.error(new UnsupportedOperationException(
                "ReactiveEntityOperations.deleteAll(Class, QuerySpec) must be overridden by the implementation"));
    }

    /**
     * Updater builder로 지정한 부분 UPDATE를 실행한다. SET 절은 최소 1개의 field 할당과 non-null
     * WHERE 절을 요구한다. 반환값은 영향 받은 행 수다.
     * <p>
     * 기본 구현은 외부 {@link ReactiveEntityOperations} 직접 구현자가 자동으로 깨지지 않도록
     * 명시적 예외를 던지며, {@link SimpleReactiveEntityOperations}는 이 메서드를 override한다.
     */
    default <T> Mono<Long> update(Class<T> entityType, Updater<T> updater) {
        return Mono.error(new UnsupportedOperationException(
                "ReactiveEntityOperations.update(Class, Updater) must be overridden by the implementation"));
    }
}
