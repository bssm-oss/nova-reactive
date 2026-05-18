package io.nova.query;

import java.util.List;
import java.util.Objects;

/**
 * 엔티티 클래스 {@code E}로부터 부분 컬럼만 선택해 record 또는 명시적 1-생성자 클래스인 projection 타입 {@code P}로
 * 매핑하기 위한 immutable 명세다.
 * <p>
 * {@code fields}는 entity의 Java property 이름(필드 이름) 목록이며, 입력 순서를 그대로 유지한다. 동일한 이름을
 * 두 번 이상 지정하더라도 별도 정규화는 수행하지 않고 그대로 보존한다 — projection 생성자가 같은 컬럼을 두 번
 * 받기를 원하는 호출자 의도를 보존하기 위함이다.
 * <p>
 * 빌더 메서드는 모두 새 {@code Projection}을 반환하지 않는다 — 본 타입은 정적 팩토리 {@link #of(Class, Class, List)}로만
 * 생성되며 한 번 생성된 인스턴스는 자유롭게 재사용·공유할 수 있다.
 * <p>
 * field 검증(미존재·개수 불일치 등) 및 생성자 호환성 검증은 실행 시 {@code SqlRenderer}와
 * {@code ReactiveEntityOperations}가 책임진다.
 *
 * @param <E> 엔티티 타입
 * @param <P> projection 결과 타입
 */
public final class Projection<E, P> {
    private final Class<E> entityType;
    private final Class<P> projectionType;
    private final List<String> fields;

    private Projection(Class<E> entityType, Class<P> projectionType, List<String> fields) {
        this.entityType = entityType;
        this.projectionType = projectionType;
        this.fields = fields;
    }

    /**
     * 새 {@code Projection}을 생성한다. {@code fields}는 비어 있을 수 없으며 {@code null} 원소를
     * 허용하지 않는다. 인자는 모두 방어 카피된다.
     */
    public static <E, P> Projection<E, P> of(Class<E> entityType, Class<P> projectionType, List<String> fields) {
        Objects.requireNonNull(entityType, "entityType must not be null");
        Objects.requireNonNull(projectionType, "projectionType must not be null");
        Objects.requireNonNull(fields, "fields must not be null");
        if (fields.isEmpty()) {
            throw new IllegalArgumentException("Projection requires at least one field");
        }
        for (int i = 0; i < fields.size(); i++) {
            String field = fields.get(i);
            if (field == null) {
                throw new NullPointerException("Projection field at index " + i + " is null");
            }
            if (field.isBlank()) {
                throw new IllegalArgumentException("Projection field at index " + i + " is blank");
            }
        }
        return new Projection<>(entityType, projectionType, List.copyOf(fields));
    }

    public Class<E> entityType() {
        return entityType;
    }

    public Class<P> projectionType() {
        return projectionType;
    }

    /**
     * projection이 가져올 entity property 이름 목록을 입력 순서대로 반환한다. 반환되는 리스트는
     * 불변이므로 호출자가 직접 수정할 수 없다.
     */
    public List<String> fields() {
        return fields;
    }
}
