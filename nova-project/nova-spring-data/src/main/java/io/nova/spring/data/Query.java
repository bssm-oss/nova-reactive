package io.nova.spring.data;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * repository 메서드에 실행할 JPQL 또는 native SQL을 직접 지정한다. Spring Data의
 * {@code org.springframework.data.jpa.repository.Query}에 대응하는 Nova 자체 애너테이션으로,
 * {@code spring-data-commons}/{@code spring-data-jpa}에 의존하지 않는다({@code spring-context}만 필요).
 *
 * <p>메서드에 {@code @Query}가 붙으면 {@link SimpleReactiveRepository}는 파생 쿼리 파싱 대신 이
 * 쿼리를 실행한다. 파라미터는 {@code :name}(= {@link Param} 또는 Spring {@code @Param}로 이름 부여)
 * 또는 {@code ?n}(1-based 선언 순서) 바인딩으로 채워진다.
 *
 * <h2>지원 범위(v1)</h2>
 * <ul>
 *   <li><b>JPQL</b>({@code nativeQuery=false}, 기본): 엔티티 반환 {@code SELECT} → {@code Flux<T>}/
 *       {@code Mono<T>}/{@code Mono<Page<T>>}/{@code Mono<Slice<T>>}(Nova 또는 Spring 타입), 스칼라
 *       {@code SELECT} → {@code Flux<scalar>}/{@code Mono<scalar>}. Wave1 JPQL 서브시스템으로 실행한다.</li>
 *   <li><b>native</b>({@code nativeQuery=true}): 엔티티 반환 {@code SELECT}(엔티티 컬럼을 모두 select)와
 *       {@link Modifying} 벌크 UPDATE/DELETE/INSERT. native 스칼라 투영과 native+{@code Pageable}은
 *       v1 미지원(fail-fast).</li>
 *   <li>{@link Modifying}가 붙은 메서드는 {@code executeUpdate} 경로(영향 행 수)로 실행된다.
 *       {@code @Modifying} 없이 UPDATE/DELETE JPQL을 지정하면 fail-fast 거부한다.</li>
 * </ul>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Query {

    /**
     * 실행할 JPQL 문자열(기본) 또는 {@link #nativeQuery()}가 {@code true}이면 native SQL 문자열.
     * blank는 정의 오류로 거부된다.
     */
    String value();

    /**
     * {@code true}이면 {@link #value()}를 native SQL로 취급해 기존 native 실행 경로로 보낸다.
     * 기본값 {@code false}는 JPQL로 파싱·변환한다.
     */
    boolean nativeQuery() default false;

    /**
     * {@code Pageable}이 있는 쿼리에서 전체 행 수를 계산할 count 쿼리(JPQL 또는 native, {@code value()}와
     * 동일한 언어). 비우면 Nova가 원 쿼리를 페이징 없이 실행한 결과 개수로 total을 계산한다(정확하지만
     * 추가 조회 비용이 있다).
     */
    String countQuery() default "";
}
