package io.nova.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * {@link ManyToOne} owning 필드의 FK 컬럼 이름과 nullability를 명시한다. 비어 있으면 기본 naming strategy로
 * {@code <propertyName>_id} 형태의 컬럼명이 사용된다.
 *
 * <p>같은 필드의 {@link Column}과 동시에 사용할 경우 컬럼 이름이 충돌하지 않아야 한다 —
 * {@link io.nova.metadata.EntityMetadataFactory}가 컬럼 이름 중복을 거부한다.
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface JoinColumn {
    String name() default "";

    boolean nullable() default true;
}
