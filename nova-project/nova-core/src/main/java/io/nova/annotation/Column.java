package io.nova.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Column {
    String value() default "";

    boolean nullable() default true;

    /**
     * varchar 등 가변 길이 문자열 컬럼의 길이다. 기본값 255는 {@link io.nova.sql.AbstractSchemaGenerator}가
     * 명시 없이 사용하던 {@code varchar(255)}와 동일하다.
     */
    int length() default 255;

    /**
     * {@link java.math.BigDecimal} numeric 컬럼의 전체 자릿수(precision)다. {@code 0}이면 미지정으로 보고
     * dialect가 합리적 기본값(예 {@code numeric(19, 2)})을 사용한다.
     */
    int precision() default 0;

    /**
     * {@link java.math.BigDecimal} numeric 컬럼의 소수 자릿수(scale)다. {@link #precision()}이 지정된 경우에만
     * 의미가 있으며 기본값은 {@code 0}이다.
     */
    int scale() default 0;
}
