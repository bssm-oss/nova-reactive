package io.nova.metadata;

import io.nova.annotation.GenerationType;
import io.nova.convert.AttributeConverter;

import java.lang.reflect.Field;
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
    private final Field embeddedHostField;

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
            boolean softDelete
    ) {
        this(field, propertyName, columnName, javaType, id, version, nullable,
                generationType, generator, converter, createdAt, updatedAt, softDelete,
                false, null);
    }

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
            Field embeddedHostField
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
        this.embeddedHostField = embeddedHostField;
        if (this.embeddedHostField != null) {
            this.embeddedHostField.setAccessible(true);
        }
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
     */
    public Field embeddedHostField() {
        return embeddedHostField;
    }

    public Object read(Object instance) {
        try {
            if (embeddedHostField == null) {
                return field.get(instance);
            }
            Object host = embeddedHostField.get(instance);
            if (host == null) {
                return null;
            }
            return field.get(host);
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("Cannot read field " + field.getName(), exception);
        }
    }

    public void write(Object instance, Object value) {
        try {
            if (embeddedHostField == null) {
                field.set(instance, value);
                return;
            }
            Object host = embeddedHostField.get(instance);
            if (host == null) {
                host = instantiateEmbeddable(embeddedHostField.getType());
                embeddedHostField.set(instance, host);
            }
            field.set(host, value);
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
                && Objects.equals(embeddedHostField, property.embeddedHostField);
    }

    @Override
    public int hashCode() {
        return Objects.hash(field, embeddedHostField);
    }
}
