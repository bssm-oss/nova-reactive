package io.nova.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 단건 참조 관계의 owning side를 표시한다. 필드 타입은 참조하는 entity 타입이며, 실제 컬럼은
 * 외래 키 한 개로 매핑된다. FK 컬럼 이름은 같은 필드에 붙은 {@link JoinColumn#name()}으로
 * override 할 수 있으며, 생략 시 기본 naming strategy로 {@code <propertyName>_id} 형태가 된다.
 *
 * <p>{@link #targetEntity()}는 추론 가능한 경우(필드 타입이 곧 target이면) 생략할 수 있으며,
 * 기본값 {@code void.class}는 "필드 타입을 그대로 사용한다"는 sentinel이다.
 *
 * <p>{@link #optional()}이 {@code false}이면 FK 컬럼은 NOT NULL로 매핑된다 (스키마 생성 단계에서
 * 사용됨). {@link JoinColumn#nullable()}이 같이 지정되면 둘 중 더 strict한 값이 적용된다.
 *
 * <p>같은 필드에 {@link OneToMany}, {@link Embedded}, {@link Id}, {@link Version},
 * {@link SoftDelete}, {@link CreatedAt}, {@link UpdatedAt}, {@link Enumerated}와 함께
 * 선언할 수 없다.
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ManyToOne {
    /**
     * 참조 대상 entity 타입. 기본값 {@code void.class}는 "필드 타입을 그대로 사용한다"는 의미의 sentinel이다.
     */
    Class<?> targetEntity() default void.class;

    /**
     * {@code false}이면 FK 컬럼은 NOT NULL로 매핑된다.
     */
    boolean optional() default true;
}
