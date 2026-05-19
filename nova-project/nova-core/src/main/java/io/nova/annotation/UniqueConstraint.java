package io.nova.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 테이블 레벨 unique constraint를 선언한다. 엔티티 타입에 부착하며 {@link Repeatable}이므로
 * 같은 엔티티에 여러 개를 선언할 수 있다.
 *
 * <p>{@link #name()}이 빈 문자열이면 메타데이터 추출 단계에서 {@code uk_{table}_{col1}_{col2}_...}
 * 패턴으로 자동 생성된다. {@link #columns()}는 반드시 1개 이상이어야 하며 실제 컬럼 이름
 * (snake_case 변환 결과)을 사용해야 한다.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Repeatable(UniqueConstraints.class)
public @interface UniqueConstraint {
    String name() default "";

    String[] columns();
}
