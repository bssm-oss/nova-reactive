package io.nova.spring.data;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * {@link Query} 메서드의 파라미터에 named 바인딩 이름을 부여한다. Spring Data의
 * {@code org.springframework.data.repository.query.Param}에 대응하는 Nova 자체 애너테이션이다.
 *
 * <p>Nova는 이 애너테이션과 함께 Spring의 {@code @Param}도(클래스패스에 있으면) 이름 기준으로 존중한다 —
 * Spring 타입을 강제 로드하지 않도록 애너테이션 타입 이름 문자열로 탐지한다. 이름이 부여되지 않은
 * 파라미터는 {@code ?n}(1-based 선언 순서) positional 바인딩으로만 참조할 수 있다.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Param {

    /**
     * 쿼리 안에서 {@code :name}으로 참조할 바인딩 이름.
     */
    String value();
}
