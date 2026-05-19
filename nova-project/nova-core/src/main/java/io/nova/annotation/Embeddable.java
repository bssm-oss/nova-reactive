package io.nova.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 자체 식별자 없이 호스트 엔티티 테이블에 컬럼들로 펼쳐지는 composite value type을 표시한다.
 * 이 타입은 {@link Embedded}로 마킹된 엔티티 필드에서만 사용해야 하며 {@link Id}를 가질 수 없다.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface Embeddable {
}
