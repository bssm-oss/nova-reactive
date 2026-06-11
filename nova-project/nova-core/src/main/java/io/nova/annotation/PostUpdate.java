package io.nova.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * UPDATE가 성공적으로 끝난 직후에 호출될 lifecycle callback 메서드를 표시한다. 대상 메서드는 entity
 * 클래스의 non-static, no-arg, void 반환 메서드여야 한다. {@code @Version}이 선언된 경우 callback 시점에는
 * 증가된 새 버전 값이 entity에 반영되어 있다.
 *
 * <p>{@code @Version} optimistic locking이 affected rows {@code 0}으로 실패하면 UPDATE가 적용되지 않은
 * 것이므로 이 callback은 호출되지 않는다.
 *
 * <p>같은 entity에 callback을 여러 개 선언할 수 있고, declaration 순서대로 호출된다.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface PostUpdate {
}
