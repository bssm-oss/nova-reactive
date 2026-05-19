package io.nova.spring.data;

import io.nova.query.Pageable;
import io.nova.query.QuerySpec;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Spring Data 스타일 reactive repository 진입점이다. 구현체는 {@link NovaRepositoryFactoryBean}이
 * 런타임에 생성하는 JDK proxy이며, 모든 메서드는 {@link io.nova.core.ReactiveEntityOperations}로
 * 위임된다.
 *
 * <p>본 인터페이스는 Spring Data Commons에 의존하지 않는다 — Spring Framework {@code spring-context}만
 * 사용한다. {@code Page<T>}를 반환하는 페이지네이션 메서드는 {@code io.nova.query.Page<T>} 도입
 * 사이클 이후에 별도로 추가된다.
 */
public interface ReactiveCrudRepository<T, ID> {

    /**
     * 식별자 상태를 기준으로 insert 또는 update를 선택해 저장한다.
     */
    Mono<T> save(T entity);

    /**
     * 여러 엔티티를 한 번에 저장한다.
     */
    Flux<T> saveAll(Iterable<T> entities);

    /**
     * 식별자로 단건 조회한다.
     */
    Mono<T> findById(ID id);

    /**
     * 식별자에 해당하는 행이 존재하는지 반환한다.
     */
    Mono<Boolean> existsById(ID id);

    /**
     * 모든 엔티티를 조회한다.
     */
    Flux<T> findAll();

    /**
     * 명세에 일치하는 엔티티를 조회한다.
     */
    Flux<T> findAll(QuerySpec spec);

    /**
     * pageable로 limit/offset만 지정한 단순 페이지네이션 조회를 수행한다. 명세는 비어 있고
     * pageable만 적용된 {@link QuerySpec}으로 위임된다.
     */
    Flux<T> findAll(Pageable pageable);

    /**
     * 여러 식별자에 해당하는 엔티티를 한 번에 조회한다.
     */
    Flux<T> findAllById(Iterable<ID> ids);

    /**
     * 테이블 전체 행 수를 반환한다.
     */
    Mono<Long> count();

    /**
     * 식별자에 해당하는 행을 삭제하고 영향 행 수를 반환한다.
     */
    Mono<Long> deleteById(ID id);

    /**
     * 엔티티의 식별자 값을 사용해 삭제하고 영향 행 수를 반환한다.
     */
    Mono<Long> delete(T entity);

    /**
     * 여러 엔티티를 한 번에 삭제하고 영향 행 수의 합을 반환한다.
     */
    Mono<Long> deleteAll(Iterable<T> entities);
}
