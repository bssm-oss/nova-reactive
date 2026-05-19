package io.nova.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * {@link UniqueConstraint}의 {@link java.lang.annotation.Repeatable} 컨테이너 어노테이션.
 * 사용자가 직접 선언할 필요는 없고 컴파일러가 반복된 {@code @UniqueConstraint}를 자동으로 감싼다.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface UniqueConstraints {
    UniqueConstraint[] value();
}
