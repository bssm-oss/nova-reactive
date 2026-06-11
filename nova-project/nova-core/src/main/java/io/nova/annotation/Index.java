package io.nova.annotation;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 테이블 레벨 secondary index를 선언한다. JPA와 동일하게 직접 타입에 붙이지 않고
 * {@link Table#indexes()}의 멤버로만 사용한다 ({@code @Target({})}).
 *
 * <p>{@link #name()}이 빈 문자열이면 메타데이터 추출 단계에서 {@code ix_{table}_{col1}_{col2}_...}
 * 패턴으로 자동 생성된다. {@link #columnList()}는 JPA와 동일하게 콤마로 구분한 컬럼 이름 목록이며
 * 최소 1개 이상이어야 하고 실제 컬럼 이름(snake_case 변환 결과)을 사용해야 한다.
 */
@Target({})
@Retention(RetentionPolicy.RUNTIME)
public @interface Index {
    String name() default "";

    String columnList();
}
