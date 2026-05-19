package io.nova.metadata;

import io.nova.annotation.EnumType;
import io.nova.annotation.GenerationType;
import io.nova.convert.AttributeConverter;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Objects;

public final class PersistentProperty {
    private final Field field;
    private final String propertyName;
    private final String columnName;
    private final Class<?> javaType;
    private final boolean id;
    private final boolean version;
    private final boolean nullable;
    private final GenerationType generationType;
    private final String generator;
    private final AttributeConverter<Object, Object> converter;
    private final boolean createdAt;
    private final boolean updatedAt;
    private final boolean softDelete;
    private final boolean embedded;
    /**
     * 호스트 엔티티 인스턴스에서 이 property가 가리키는 leaf field까지 traverse해야 하는
     * {@link io.nova.annotation.Embedded} 필드들의 outer → inner 순서 체인. top-level property는
     * 비어있다. nested 1-level은 길이 1, 2-level은 길이 2.
     */
    private final List<Field> embeddedHostPath;
    private final boolean enumerated;
    private final EnumType enumType;

    @SuppressWarnings("unchecked")
    public PersistentProperty(
            Field field,
            String propertyName,
            String columnName,
            Class<?> javaType,
            boolean id,
            boolean version,
            boolean nullable,
            GenerationType generationType,
            String generator,
            AttributeConverter<?, ?> converter,
            boolean createdAt,
            boolean updatedAt,
            boolean softDelete,
            boolean embedded,
            List<Field> embeddedHostPath,
            boolean enumerated,
            EnumType enumType
    ) {
        this.field = field;
        this.field.setAccessible(true);
        this.propertyName = propertyName;
        this.columnName = columnName;
        this.javaType = javaType;
        this.id = id;
        this.version = version;
        this.nullable = nullable;
        this.generationType = generationType;
        this.generator = generator == null ? "" : generator;
        this.converter = (AttributeConverter<Object, Object>) converter;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.softDelete = softDelete;
        this.embedded = embedded;
        this.embeddedHostPath = embeddedHostPath == null ? List.of() : List.copyOf(embeddedHostPath);
        for (Field hostField : this.embeddedHostPath) {
            hostField.setAccessible(true);
        }
        this.enumerated = enumerated;
        this.enumType = enumType;
    }

    public Field field() {
        return field;
    }

    public String propertyName() {
        return propertyName;
    }

    public String columnName() {
        return columnName;
    }

    public Class<?> javaType() {
        return javaType;
    }

    public boolean id() {
        return id;
    }

    public boolean version() {
        return version;
    }

    public boolean nullable() {
        return nullable;
    }

    public GenerationType generationType() {
        return generationType;
    }

    public String generator() {
        return generator;
    }

    public boolean generated() {
        return generationType != GenerationType.NONE;
    }

    public boolean createdAt() {
        return createdAt;
    }

    public boolean updatedAt() {
        return updatedAt;
    }

    public boolean softDelete() {
        return softDelete;
    }

    /**
     * {@code true}이면 이 property는 호스트 엔티티의 {@link io.nova.annotation.Embedded}
     * 필드에 위치한 {@link io.nova.annotation.Embeddable} 타입에서 펼쳐져 나온 것이다.
     */
    public boolean embedded() {
        return embedded;
    }

    /**
     * 이 property의 값을 read/write할 때 먼저 거쳐야 하는 호스트 엔티티의
     * {@link io.nova.annotation.Embedded} 필드. top-level property는 {@code null}.
     * 다단계 nested embedded일 때는 가장 안쪽(leaf field를 직접 담는) 호스트 필드를 반환한다.
     * 전체 chain은 {@link #embeddedHostPath()}를 사용한다.
     */
    public Field embeddedHostField() {
        if (embeddedHostPath.isEmpty()) {
            return null;
        }
        return embeddedHostPath.get(embeddedHostPath.size() - 1);
    }

    /**
     * 호스트 엔티티 인스턴스에서 leaf field까지 도달하기 위해 outer → inner 순서로 거쳐야 하는
     * {@link io.nova.annotation.Embedded} 호스트 필드 체인. top-level property는 빈 리스트를 반환한다.
     * 1-level embedded는 길이 1, 2-level nested embedded는 길이 2.
     */
    public List<Field> embeddedHostPath() {
        return embeddedHostPath;
    }

    /**
     * {@code true}이면 이 property는 {@link io.nova.annotation.Enumerated}로 마킹된 enum 필드이며
     * {@link #enumType()}이 {@link io.nova.annotation.EnumType#STRING} 또는 {@code ORDINAL} 중 하나를
     * 반환한다.
     */
    public boolean enumerated() {
        return enumerated;
    }

    /**
     * enumerated property의 저장 전략. enum이 아닌 property는 {@code null}이다.
     */
    public EnumType enumType() {
        return enumType;
    }

    public Object read(Object instance) {
        try {
            Object current = instance;
            for (Field hostField : embeddedHostPath) {
                current = hostField.get(current);
                if (current == null) {
                    return null;
                }
            }
            return field.get(current);
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("Cannot read field " + field.getName(), exception);
        }
    }

    public void write(Object instance, Object value) {
        try {
            Object current = instance;
            for (Field hostField : embeddedHostPath) {
                Object next = hostField.get(current);
                if (next == null) {
                    next = instantiateEmbeddable(hostField.getType());
                    hostField.set(current, next);
                }
                current = next;
            }
            field.set(current, value);
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("Cannot write field " + field.getName(), exception);
        }
    }

    private static Object instantiateEmbeddable(Class<?> embeddableType) {
        try {
            var constructor = embeddableType.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(
                    "Embeddable type must expose a no-args constructor: " + embeddableType.getName(), exception);
        }
    }

    public Object toColumnValue(Object value) {
        if (value == null || converter == null) {
            return value;
        }
        return converter.write(value);
    }

    public Object toPropertyValue(Object value) {
        if (value == null || converter == null) {
            return value;
        }
        return converter.read(value);
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof PersistentProperty property)) {
            return false;
        }
        return Objects.equals(field, property.field)
                && Objects.equals(embeddedHostPath, property.embeddedHostPath);
    }

    @Override
    public int hashCode() {
        return Objects.hash(field, embeddedHostPath);
    }
}
