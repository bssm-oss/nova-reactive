package io.nova.metadata;

import java.util.Objects;

/**
 * Physical storage characteristics of one database column.
 *
 * <p>The Java type is the value type presented to the driver, rather than
 * necessarily the domain-property type. Length, precision, and scale retain
 * the complete effective {@code @Column} shape for schema generation.
 */
public record ColumnStorage(Class<?> javaType, int length, int precision, int scale) {

    public ColumnStorage {
        javaType = boxed(Objects.requireNonNull(javaType, "javaType"));
    }

    /** Captures the physical storage characteristics of a scalar property. */
    public static ColumnStorage from(PersistentProperty property) {
        Objects.requireNonNull(property, "property");
        return new ColumnStorage(property.columnType(), property.length(), property.precision(), property.scale());
    }

    private static Class<?> boxed(Class<?> type) {
        if (!type.isPrimitive()) {
            return type;
        }
        if (type == boolean.class) return Boolean.class;
        if (type == byte.class) return Byte.class;
        if (type == short.class) return Short.class;
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == float.class) return Float.class;
        if (type == double.class) return Double.class;
        if (type == char.class) return Character.class;
        return type;
    }
}
