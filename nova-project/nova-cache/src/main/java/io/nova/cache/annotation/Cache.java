package io.nova.cache.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 엔티티의 2차 캐시 region과 동시성 전략을 지정한다. Hibernate {@code org.hibernate.annotations.Cache}의
 * 리액티브 등가이며, Nova는 Hibernate에 의존하지 않으므로 자체 애너테이션으로 노출한다.
 *
 * <p>이 애너테이션이 붙은 엔티티는 {@code jakarta.persistence.Cacheable} 없이도 캐시 대상으로 간주된다
 * (즉 {@code @Cache}는 캐시 활성화를 함의한다). 반대로 {@code @Cacheable(false)}가 함께 있으면 캐시가
 * 비활성화된다({@code @Cacheable}이 우선). region을 생략하면 엔티티 클래스의 {@code getName()}이 region
 * 이름으로 쓰인다.
 *
 * <p>{@link #usage()}에 v1 미지원 전략({@link CacheConcurrencyStrategy#NONSTRICT_READ_WRITE},
 * {@link CacheConcurrencyStrategy#TRANSACTIONAL})을 지정하면 캐시 배선 시점에 fail-fast로 거부된다.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Inherited
public @interface Cache {

    /**
     * 캐시 region 이름. 빈 문자열이면 엔티티 클래스의 정규 이름({@code Class#getName()})을 region으로 사용한다.
     */
    String region() default "";

    /**
     * 동시성 전략. 기본은 {@link CacheConcurrencyStrategy#READ_WRITE}.
     */
    CacheConcurrencyStrategy usage() default CacheConcurrencyStrategy.READ_WRITE;
}
