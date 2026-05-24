package io.nova.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 엔티티 필드의 값을 JSON 문자열로 직렬화해 단일 컬럼에 저장하도록 표시하는 marker 어노테이션이다.
 * 직렬화/역직렬화는 등록된 {@link io.nova.json.JsonCodec} SPI 구현이 담당하며, Nova 코어는 특정 JSON
 * 라이브러리에 의존하지 않는다.
 * <p>
 * 컬럼 이름은 {@link Column}/naming strategy가 결정하고, 컬럼의 SQL 타입은 dialect의
 * {@link io.nova.sql.Dialect#jsonColumnType()}(기본 {@code json}, PostgreSQL {@code jsonb})이
 * 결정한다. {@link Enumerated}, 사용자가 등록한 {@link io.nova.convert.AttributeConverter},
 * 그리고 관계 어노테이션({@link ManyToOne}/{@link OneToMany})과는 함께 사용할 수 없다 —
 * 변환 책임이 충돌하기 때문이다.
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Json {
}
