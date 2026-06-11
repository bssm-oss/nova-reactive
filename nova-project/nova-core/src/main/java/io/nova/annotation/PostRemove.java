package io.nova.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * delete가 성공적으로 끝난 직후에 호출될 lifecycle callback 메서드를 표시한다. 대상 메서드는 entity
 * 클래스의 non-static, no-arg, void 반환 메서드여야 한다. {@code @SoftDelete}로 변환된 UPDATE 경로에서도
 * 동일하게 호출된다.
 *
 * <p>{@code @Version} optimistic locking이 affected rows {@code 0}으로 실패하면 행이 삭제되지 않은
 * 것이므로 이 callback은 호출되지 않는다. id만으로 삭제하는 {@code deleteById(...)}는 호출 대상 entity
 * 인스턴스가 없으므로 {@code @PreRemove}/{@code @PostRemove}를 발화하지 않는다.
 *
 * <p>같은 entity에 callback을 여러 개 선언할 수 있고, declaration 순서대로 호출된다.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface PostRemove {
}
