package io.nova.query;

import java.util.LinkedHashMap;
import java.util.Objects;

/**
 * Criteria 기반의 부분 UPDATE를 만드는 immutable builder다. {@code Updater}는 entity 인스턴스 없이
 * inline value로 SET 절을 구성하고 {@link Predicate}로 WHERE 절을 지정한다.
 * <p>
 * 모든 빌더 메서드는 새 {@code Updater}를 반환하므로 인스턴스를 자유롭게 재사용·공유할 수 있다.
 * field 이름은 entity의 Java property 이름(필드 이름)이며, 동일 field에 대해 {@link #set(String, Object)}을
 * 두 번 호출하면 마지막 값이 이전 값을 덮어쓴다.
 * <p>
 * 실행은 {@code ReactiveEntityOperations#update(Class, Updater)}로 위임한다.
 */
public final class Updater<T> {
    private final Class<T> entityType;
    private final LinkedHashMap<String, Object> fieldValues;
    private final Predicate where;

    private Updater(Class<T> entityType, LinkedHashMap<String, Object> fieldValues, Predicate where) {
        this.entityType = entityType;
        this.fieldValues = fieldValues;
        this.where = where;
    }

    /**
     * 새 빌더를 시작한다.
     */
    public static <T> Updater<T> of(Class<T> entityType) {
        Objects.requireNonNull(entityType, "entityType must not be null");
        return new Updater<>(entityType, new LinkedHashMap<>(), null);
    }

    /**
     * SET 절에 {@code field = value} 한 쌍을 더한 새 {@code Updater}를 반환한다.
     * 동일 field로 다시 호출하면 마지막 값이 채택된다.
     */
    public Updater<T> set(String field, Object value) {
        Objects.requireNonNull(field, "field must not be null");
        if (field.isBlank()) {
            throw new IllegalArgumentException("field must not be blank");
        }
        LinkedHashMap<String, Object> next = new LinkedHashMap<>(fieldValues);
        next.remove(field);
        next.put(field, value);
        return new Updater<>(entityType, next, where);
    }

    /**
     * WHERE 절을 지정한 새 {@code Updater}를 반환한다. null은 허용되지 않는다.
     */
    public Updater<T> where(Predicate predicate) {
        Objects.requireNonNull(predicate, "where predicate must not be null");
        return new Updater<>(entityType, fieldValues, predicate);
    }

    public Class<T> entityType() {
        return entityType;
    }

    /**
     * SET 절에 사용할 field-value 쌍을 입력 순서대로 반환한다. 반환값은 방어 카피이므로
     * 호출자가 수정하더라도 빌더의 내부 상태에 영향을 주지 않는다.
     */
    public LinkedHashMap<String, Object> fields() {
        return new LinkedHashMap<>(fieldValues);
    }

    /**
     * 지정된 WHERE 절. 빌더가 {@link #where(Predicate)} 호출 전이라면 {@code null}.
     */
    public Predicate where() {
        return where;
    }
}
