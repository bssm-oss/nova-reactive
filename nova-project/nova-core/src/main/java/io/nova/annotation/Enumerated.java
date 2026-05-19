package io.nova.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 엔티티의 enum 타입 필드를 컬럼 값으로 변환하는 방식을 선언한다. 필드 타입이 {@link Enum}이 아니면
 * 메타데이터 생성 시 거부된다. 사용자가 직접 등록한 {@link io.nova.convert.AttributeConverter}와
 * 동시에 사용할 수 없다 (변환 책임 충돌 방지).
 * <p>
 * 기본 전략은 {@link EnumType#STRING}으로 enum constant 이름을 그대로 보존해 상수 재정렬에 안전하다.
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Enumerated {
    EnumType value() default EnumType.STRING;
}
