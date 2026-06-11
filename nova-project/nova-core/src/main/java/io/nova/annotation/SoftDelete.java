package io.nova.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 엔티티의 논리적 삭제 시각을 보관하는 컬럼임을 표시한다.
 * <p>
 * {@code @SoftDelete}가 붙은 필드의 값이 {@code null}이면 해당 행은 살아 있고,
 * non-null이면 삭제 시각을 의미한다. {@link io.nova.core.ReactiveEntityOperations}의
 * 삭제 API는 이 컬럼을 채우는 UPDATE 문으로 자동 변환되며, 조회 API는
 * {@code AND <column> IS NULL} 술어를 자동으로 추가한다.
 * <p>
 * 지원 타입은 {@link java.time.Instant}, {@link java.time.LocalDateTime},
 * {@link java.time.OffsetDateTime}이다. 엔티티당 하나만 선언할 수 있으며,
 * {@link jakarta.persistence.Id}와 동시에 선언할 수 없다.
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface SoftDelete {
}
