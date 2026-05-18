package io.nova.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface GeneratedValue {
    GenerationType strategy() default GenerationType.AUTO;

    /**
     * SEQUENCE 전략에서 사용할 데이터베이스 시퀀스 이름이다. 다른 전략에서는 무시된다.
     */
    String generator() default "";
}
