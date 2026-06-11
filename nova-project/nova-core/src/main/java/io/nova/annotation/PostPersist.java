package io.nova.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * INSERT가 성공적으로 끝난 직후에 호출될 lifecycle callback 메서드를 표시한다. 대상 메서드는 entity
 * 클래스의 non-static, no-arg, void 반환 메서드여야 한다. generated identity/UUID/sequence 값은 이 시점에
 * 이미 entity에 주입되어 있으므로 callback에서 id를 읽을 수 있다.
 *
 * <p>callback이 예외를 던지면 그 예외가 {@code save(...)}의 {@code Mono}로 전파된다. INSERT 자체는 이미
 * 커밋 경로에 올라간 뒤이므로 callback 예외가 INSERT를 되돌리지는 않는다.
 *
 * <p>같은 entity에 callback을 여러 개 선언할 수 있고, declaration 순서대로 호출된다.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface PostPersist {
}
