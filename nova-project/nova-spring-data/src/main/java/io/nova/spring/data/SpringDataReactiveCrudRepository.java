package io.nova.spring.data;

import io.nova.query.QuerySpec;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * {@link ReactiveCrudRepository}에 표준 Spring Data Commons 페이징/정렬 타입
 * ({@code org.springframework.data.domain.Pageable}/{@code Sort}/{@code Page}/{@code Slice})을
 * 받는 <b>추가적(additive)</b> 오버로드를 더한 <b>opt-in</b> 서브인터페이스다.
 *
 * <h2>왜 별도 서브인터페이스인가 (optionality 보장)</h2>
 * <p>{@link NovaRepositoryFactoryBean}은 각 repository 인터페이스에 대해
 * {@link java.lang.reflect.Proxy#newProxyInstance}로 JDK proxy를 <b>eager</b> 생성한다. proxy
 * 생성 과정에서 인터페이스의 모든 메서드에 대해 파라미터/리턴 {@code Class}가 resolve되므로,
 * 만약 표준 타입 오버로드를 공용 {@link ReactiveCrudRepository}에 직접 선언하면 브릿지 메서드를
 * 전혀 쓰지 않는 Nova-only repository의 proxy 생성 시점에도
 * {@code org.springframework.data.domain.*}가 강제 로드된다. {@code spring-data-commons}가
 * 런타임 클래스패스에 없으면(이 모듈은 {@code spring-context}만 api로 export한다) bean 초기화가
 * {@link NoClassDefFoundError}로 실패한다.
 *
 * <p>표준 타입 오버로드를 이 opt-in 서브인터페이스로 분리하면, 순수 {@link ReactiveCrudRepository}
 * 서브인터페이스의 proxy 생성은 Spring 타입을 절대 resolve하지 않는다. 소비자는
 * {@code spring-data-commons}를 클래스패스에 둔 경우에만 이 인터페이스를 {@code extends}하면 된다.
 * {@link SimpleReactiveRepository}의 dispatch는 파라미터 타입 <em>이름 문자열</em>로 라우팅하므로,
 * 브릿지 메서드가 어느 인터페이스에 선언되든 동일하게 동작한다.
 *
 * @param <T>  엔티티 타입
 * @param <ID> 식별자 타입
 */
public interface SpringDataReactiveCrudRepository<T, ID> extends ReactiveCrudRepository<T, ID> {

    /**
     * 표준 Spring Data {@code Sort}를 적용해 전체 엔티티를 정렬 조회한다. {@code Sort}는 Nova
     * {@link io.nova.query.Sort}로 변환되어 기존 {@link io.nova.query.QuerySpec} 정렬 경로에
     * 위임된다. {@code Sort.unsorted()}는 정렬 없음으로 취급한다.
     *
     * <p>Nova {@link io.nova.query.Sort}가 표현하지 못하는 옵션(ignore-case, 비-NATIVE
     * null-handling)이 지정되면 반환 {@link Flux}가 {@link IllegalArgumentException}으로
     * onError 신호를 낸다(조립 시점 동기 throw가 아니라 리액티브 체인 안에서 전파).
     */
    Flux<T> findAll(org.springframework.data.domain.Sort sort);

    /**
     * 표준 Spring Data {@code Pageable}로 한 페이지를 조회해 표준 {@code Page}로 반환한다.
     * {@code Pageable}의 page/size/sort가 Nova 페이징으로 변환되어 위임되며, 총 행 수를 위한
     * COUNT가 함께 발행된다. {@code Pageable.unpaged()}이면 전체를 단일 페이지로 반환한다.
     */
    Mono<org.springframework.data.domain.Page<T>> findAll(
            org.springframework.data.domain.Pageable pageable);

    /**
     * Nova {@link io.nova.query.QuerySpec} 명세와 표준 Spring Data {@code Pageable}을 함께
     * 적용해 표준 {@code Page}로 반환한다. {@code Pageable}에 정렬이 지정되면 명세의 기존 정렬을
     * 대체하고, {@code Sort.unsorted()}이면 명세의 정렬을 보존한다.
     */
    Mono<org.springframework.data.domain.Page<T>> findAll(
            QuerySpec spec, org.springframework.data.domain.Pageable pageable);

    /**
     * 표준 Spring Data {@code Pageable}로 한 페이지를 조회해 표준 {@code Slice}로 반환한다.
     * {@code Slice}는 총 행 수 COUNT 없이 다음 페이지 존재 여부만 계산한다. slice는 페이지 크기
     * 제한이 필수이므로 {@code Pageable.unpaged()}이면 반환 {@link Mono}가
     * {@link IllegalArgumentException}으로 onError 신호를 낸다.
     */
    Mono<org.springframework.data.domain.Slice<T>> findSlice(
            org.springframework.data.domain.Pageable pageable);

    /**
     * Nova {@link io.nova.query.QuerySpec} 명세와 표준 Spring Data {@code Pageable}을 함께
     * 적용해 표준 {@code Slice}로 반환한다. {@code Pageable.unpaged()}이면 반환 {@link Mono}가
     * {@link IllegalArgumentException}으로 onError 신호를 낸다.
     */
    Mono<org.springframework.data.domain.Slice<T>> findSlice(
            QuerySpec spec, org.springframework.data.domain.Pageable pageable);
}
