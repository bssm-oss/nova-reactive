package io.nova.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * insert 및 update 시점에 자동으로 현재 시각이 채워질 audit 필드를 표시한다. 필드 타입은
 * {@link java.time.Instant}, {@link java.time.LocalDateTime}, {@link java.time.OffsetDateTime}
 * 중 하나여야 한다. 기존 값은 항상 덮어쓴다.
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface UpdatedAt {
}
