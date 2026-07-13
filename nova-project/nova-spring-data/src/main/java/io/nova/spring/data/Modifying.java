package io.nova.spring.data;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * {@link Query}와 함께 붙어 해당 쿼리가 수정(bulk UPDATE/DELETE/INSERT) 쿼리임을 표시한다. Spring Data의
 * {@code org.springframework.data.jpa.repository.Modifying}에 대응하는 Nova 자체 애너테이션이다.
 *
 * <p>{@code @Modifying}가 있으면 쿼리는 조회가 아니라 {@code executeUpdate} 경로(JPQL은
 * {@code JpqlQuery.executeUpdate}, native는 {@code executeNative})로 실행되어 영향 행 수를 발행한다.
 * 반환 타입은 {@code Mono<Long>}/{@code Mono<Integer>}/{@code Mono<Void>}를 지원한다.
 *
 * <p>{@code @Modifying} 없이 UPDATE/DELETE JPQL을 {@link Query}로 지정하면 {@link SimpleReactiveRepository}가
 * fail-fast로 거부한다 — 조용한 오실행을 막기 위함이다.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Modifying {
}
