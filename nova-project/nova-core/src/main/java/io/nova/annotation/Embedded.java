package io.nova.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 엔티티 필드가 {@link Embeddable} composite value type을 호스트 테이블의 컬럼들로 펼쳐
 * 매핑됨을 표시한다. 컬럼 이름은 {@code {field name (snake_case)}_{sub property column name}}
 * 규칙으로 합성된다.
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Embedded {
}
