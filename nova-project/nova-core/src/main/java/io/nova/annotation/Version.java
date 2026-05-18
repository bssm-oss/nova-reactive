package io.nova.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 엔티티에 optimistic locking용 버전 컬럼임을 표시한다. 지원 타입은 {@code Long}, {@code Integer},
 * {@code Short}이며 한 엔티티에 최대 1개만 선언할 수 있고 {@link Id}와 함께 쓸 수 없다.
 * <p>
 * insert 시 버전 필드가 {@code null}이면 {@code 0}으로 초기화되고, update와 entity-기반 delete는
 * {@code WHERE id = ? AND v = ?} 형태로 현재 버전을 검증한다. 검증에 실패해 affected rows가 {@code 0}이면
 * {@link io.nova.exception.OptimisticLockingFailureException}을 발행한다.
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Version {
}
