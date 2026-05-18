package io.nova.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * insert 직전에 호출될 lifecycle callback 메서드를 표시한다. 대상 메서드는 entity 클래스의
 * non-static, no-arg, void 반환 메서드여야 한다. callback 안에서 entity 필드를 수정하면 그 결과가
 * 즉시 INSERT의 컬럼 바인딩에 반영된다.
 *
 * <p>{@code @CreatedAt}/{@code @UpdatedAt} 자동 audit과 함께 사용될 때는 audit applier가 먼저
 * 실행된 후 사용자 {@code @PrePersist} callback이 호출된다. 따라서 callback에서 audit 필드를
 * 덮어쓰면 사용자 값이 우선한다.
 *
 * <p>같은 entity에 callback을 여러 개 선언할 수 있고, declaration 순서대로 호출된다.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface PrePersist {
}
