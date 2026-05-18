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
            boolean softDelete
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

    public Object read(Object instance) {
        try {
            return field.get(instance);
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("Cannot read field " + field.getName(), exception);
        }
    }

    public void write(Object instance, Object value) {
        try {
            field.set(instance, value);
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("Cannot write field " + field.getName(), exception);
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
        return Objects.equals(field, property.field);
    }

    @Override
    public int hashCode() {
        return field.hashCode();
    }
}
