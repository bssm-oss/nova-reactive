package io.nova.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * delete 직전에 호출될 lifecycle callback 메서드를 표시한다. 대상 메서드는 entity 클래스의
 * non-static, no-arg, void 반환 메서드여야 한다. {@code @SoftDelete}로 변환된 UPDATE 경로에서도
 * 동일하게 호출된다.
 *
 * <p>같은 entity에 callback을 여러 개 선언할 수 있고, declaration 순서대로 호출된다.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface PreRemove {
}
