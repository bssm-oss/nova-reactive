package io.nova.convert;

import jakarta.persistence.EnumType;
import jakarta.persistence.EnumeratedValue;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.Map;

/** Maps an enum through its Jakarta Persistence 3.2 {@link EnumeratedValue} field. */
public final class EnumeratedValueConverter<E extends Enum<E>> implements AttributeConverter<E, Object> {
    private final Field valueField;
    private final Map<Object, E> constantsByValue;
    private final Class<?> columnType;

    public EnumeratedValueConverter(Class<E> enumClass, EnumType enumType) {
        Field selected = null;
        for (Field field : enumClass.getDeclaredFields()) {
            if (!field.isAnnotationPresent(EnumeratedValue.class)) {
                continue;
            }
            if (selected != null) {
                throw invalid(enumClass, "declares more than one @EnumeratedValue field");
            }
            selected = field;
        }
        if (selected == null) {
            throw invalid(enumClass, "does not declare an @EnumeratedValue field");
        }
        if (Modifier.isStatic(selected.getModifiers()) || !Modifier.isFinal(selected.getModifiers())) {
            throw invalid(enumClass, "@EnumeratedValue field " + selected.getName() + " must be a non-static final field");
        }
        Class<?> fieldType = selected.getType();
        if (enumType == EnumType.STRING && fieldType != String.class) {
            throw invalid(enumClass, "STRING mapping requires a String @EnumeratedValue field");
        }
        if (enumType == EnumType.ORDINAL
                && fieldType != byte.class && fieldType != short.class && fieldType != int.class) {
            throw invalid(enumClass, "ORDINAL mapping requires a byte, short, or int @EnumeratedValue field");
        }
        selected.setAccessible(true);
        this.valueField = selected;
        // SQL/R2DBC has no portable tinyint path in Nova. byte is losslessly represented as SMALLINT.
        this.columnType = fieldType == byte.class ? Short.class
                : fieldType == short.class ? Short.class
                : fieldType == int.class ? Integer.class : String.class;
        this.constantsByValue = validateConstants(enumClass);
    }

    public Class<?> columnType() {
        return columnType;
    }

    @Override
    public Object write(E source) {
        Object value = fieldValue(source);
        return value instanceof Byte number ? number.shortValue() : value;
    }

    @Override
    public E read(Object source) {
        Object key = normalize(source);
        E value = constantsByValue.get(key);
        if (value == null) {
            throw new IllegalStateException("Unknown @EnumeratedValue database value '" + source
                    + "' for " + valueField.getDeclaringClass().getName());
        }
        return value;
    }

    private Map<Object, E> validateConstants(Class<E> enumClass) {
        Map<Object, E> values = new LinkedHashMap<>();
        for (E constant : enumClass.getEnumConstants()) {
            Object raw = fieldValue(constant);
            if (raw == null) {
                throw invalid(enumClass, "@EnumeratedValue field " + valueField.getName()
                        + " must not be null for " + constant.name());
            }
            Object key = normalize(raw);
            E previous = values.putIfAbsent(key, constant);
            if (previous != null) {
                throw invalid(enumClass, "@EnumeratedValue field " + valueField.getName()
                        + " has duplicate value '" + raw + "' for " + previous.name() + " and " + constant.name());
            }
        }
        return Map.copyOf(values);
    }

    private Object fieldValue(E source) {
        try {
            return valueField.get(source);
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("Cannot read @EnumeratedValue field " + valueField, exception);
        }
    }

    private Object normalize(Object value) {
        if (columnType == Short.class && value instanceof Number number) {
            return number.shortValue();
        }
        if (columnType == Integer.class && value instanceof Number number) {
            return number.intValue();
        }
        return value;
    }

    private static IllegalArgumentException invalid(Class<?> enumClass, String detail) {
        return new IllegalArgumentException("Enum " + enumClass.getName() + " " + detail);
    }
}
