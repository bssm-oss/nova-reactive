package io.nova.spring.data;

import io.nova.query.Page;
import io.nova.query.Pageable;
import io.nova.query.QuerySpec;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Spring Data 스타일 reactive repository 진입점이다. 구현체는 {@link NovaRepositoryFactoryBean}이
 * 런타임에 생성하는 JDK proxy이며, 모든 메서드는 {@link io.nova.core.ReactiveEntityOperations}로
 * 위임된다.
 *
 * <p>이 인터페이스의 표면은 Nova 자체 타입({@link io.nova.query.Pageable}/
 * {@link io.nova.query.Page}/{@link io.nova.query.Sort})만 사용하며 Spring Data Commons에
 * 의존하지 않는다 — Spring Framework {@code spring-context}만 필요하다. 따라서 이 인터페이스를
 * 직접 {@code extends}하는 repository는 {@code spring-data-commons} 없이도 proxy 생성/부팅이
 * 안전하다.
 *
 * <p>표준 {@code org.springframework.data.domain.Pageable}/{@code Sort} 브릿지가 필요하면
 * opt-in 서브인터페이스 {@link SpringDataReactiveCrudRepository}를 {@code extends}하라. 표준
 * 타입 오버로드를 여기(공용 인터페이스)에 두지 않는 이유는 {@link SpringDataReactiveCrudRepository}
 * 문서에 설명한 eager proxy 로딩 회귀 때문이다.
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
     * 명세와 pageable을 함께 적용한 페이지네이션 결과를 반환한다. SELECT와 COUNT가 함께 발행되며
     * {@link Page#totalElements()}로 전체 행 수를 알 수 있다.
     */
    Mono<Page<T>> findAll(QuerySpec spec, Pageable pageable);

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

    // 표준 Spring Data 타입(org.springframework.data.domain.Pageable/Sort/Page/Slice) 오버로드는
    // 이 인터페이스에 두지 않는다. 그렇게 하면 브릿지 메서드를 쓰지 않는 Nova-only repository의
    // eager proxy 생성 시점에도 Spring 타입이 강제 resolve되어, spring-data-commons가 런타임에
    // 없을 때 부팅이 실패한다. 표준 타입 오버로드는 opt-in 서브인터페이스
    // io.nova.spring.data.SpringDataReactiveCrudRepository 에 있으며, spring-data-commons를
    // 클래스패스에 둔 소비자만 그 인터페이스를 extends 한다.
}
