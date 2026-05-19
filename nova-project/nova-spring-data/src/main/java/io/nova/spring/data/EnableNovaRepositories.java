package io.nova.spring.data;

import org.springframework.context.annotation.Import;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Nova의 {@link ReactiveCrudRepository} 서브 인터페이스를 스캔해 {@link NovaRepositoryFactoryBean}으로
 * 등록한다. {@code basePackages} 또는 {@code basePackageClasses} 중 적어도 하나를 지정해야 하며,
 * 둘 다 비어 있으면 본 애너테이션을 단 클래스의 패키지를 기본으로 사용한다.
 *
 * <p>등록된 프록시는 {@code entityOperationsRef}로 가리키는 {@link io.nova.core.ReactiveEntityOperations}
 * 빈으로 위임한다 — 기본값은 {@code novaEntityOperations}로 nova-spring-boot-starter의 자동 구성 빈 이름과
 * 일치한다.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Import(NovaRepositoriesRegistrar.class)
public @interface EnableNovaRepositories {

    /**
     * 스캔할 base 패키지 이름들. 비어 있으면 {@link #basePackageClasses()}를 우선 사용하며,
     * 그것마저 비어 있으면 본 애너테이션을 단 클래스의 패키지를 사용한다.
     */
    String[] basePackages() default {};

    /**
     * 스캔할 base 패키지를 type-safe하게 가리키는 클래스들. 각 클래스의 패키지가 base가 된다.
     */
    Class<?>[] basePackageClasses() default {};

    /**
     * 위임할 {@link io.nova.core.ReactiveEntityOperations} 빈 이름. 기본값은
     * {@code novaEntityOperations}.
     */
    String entityOperationsRef() default "novaEntityOperations";
}
