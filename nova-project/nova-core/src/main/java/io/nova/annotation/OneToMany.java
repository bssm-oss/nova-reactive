package io.nova.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 컬렉션 관계의 inverse side를 표시한다. 필드 타입은 보통 {@code java.util.List<Child>} 형태이며,
 * 이 필드는 owning side가 아니므로 parent 테이블에는 컬럼을 만들지 않는다 — child 측 {@link ManyToOne}
 * (또는 {@link JoinColumn})이 정의한 FK 컬럼이 실제 관계를 보관한다.
 *
 * <p>{@link #mappedBy()}는 필수 — child entity 안의 {@link ManyToOne} property 이름이다. 이 이름으로
 * child {@link io.nova.metadata.EntityMetadata}에서 FK 컬럼을 찾아 child 측 {@code WHERE fk IN (...)} 절을
 * 만든다.
 *
 * <p>같은 필드에 {@link ManyToOne}, {@link Embedded}, {@link Id}, {@link Version},
 * {@link SoftDelete}, {@link CreatedAt}, {@link UpdatedAt}, {@link Enumerated}와 함께
 * 선언할 수 없으며, 부모 테이블 컬럼으로도 매핑되지 않으므로 {@link Column} 동반은 의미가 없다.
 *
 * <p>이 필드는 parent 측에서 컬럼이 없으므로 INSERT/UPDATE 바인딩 대상에서도 제외된다. fetch 단계는
 * {@link io.nova.fetch.FetchGroup}이 mappedBy를 따라 child IN-query 한 번으로 채워준다.
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface OneToMany {
    /**
     * child collection의 원소 entity 타입. 기본값 {@code void.class}는 sentinel로, 호출자가 컬렉션
     * generic type을 지정한 경우 metadata factory가 erasure 한계로 추론하지 못할 수 있으므로
     * 보통 명시한다.
     */
    Class<?> targetEntity() default void.class;

    /**
     * inverse side가 가리키는 child entity의 owning property 이름. child 안의 {@link ManyToOne} field 이름.
     */
    String mappedBy();
}
