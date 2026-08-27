package io.nova.core;

import io.nova.metadata.ElementCollectionInfo;
import io.nova.metadata.PersistentProperty;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Internal immutable/mutable embeddable construction used by row hydration. */
final class EmbeddableInstantiationStrategy {
    private static final Map<Class<?>, Constructor<?>> RECORD_CONSTRUCTORS = new ConcurrentHashMap<>();

    private EmbeddableInstantiationStrategy() {
    }

    static void hydrateSingleValued(Object entity, List<DecodedLeaf> leaves) {
        Map<Field, List<DecodedLeaf>> byRoot = new LinkedHashMap<>();
        for (DecodedLeaf leaf : leaves) {
            Field root = leaf.property().embeddedHostPath().get(0);
            byRoot.computeIfAbsent(root, ignored -> new ArrayList<>()).add(leaf);
        }
        for (Map.Entry<Field, List<DecodedLeaf>> entry : byRoot.entrySet()) {
            Object value = constructHost(entry.getKey(), 0, entry.getValue(), true);
            setField(entity, entry.getKey(), value, "embedded host");
        }
    }

    static Object instantiateCollectionRecord(
            Class<?> type, List<ElementCollectionInfo.EmbeddableColumn> columns, List<Object> values) {
        if (!type.isRecord()) {
            throw new IllegalArgumentException(type.getName() + " is not a record");
        }
        Map<String, Object> valuesByName = new LinkedHashMap<>();
        for (int i = 0; i < columns.size(); i++) {
            valuesByName.put(columns.get(i).field().getName(), values.get(i));
        }
        return instantiateRecord(type, valuesByName, "@ElementCollection record " + type.getName());
    }

    static Object readCollectionValue(Object value, ElementCollectionInfo.EmbeddableColumn column) {
        Field field = column.field();
        if (value.getClass().isRecord()) {
            try {
                Method accessor = value.getClass().getDeclaredMethod(field.getName());
                accessor.setAccessible(true);
                return accessor.invoke(value);
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException("Cannot read record embeddable component "
                        + value.getClass().getName() + "." + field.getName(), exception);
            }
        }
        try {
            field.setAccessible(true);
            return field.get(value);
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("Cannot read @Embeddable @ElementCollection field "
                    + field.getName(), exception);
        }
    }

    private static Object constructHost(Field hostField, int depth, List<DecodedLeaf> leaves, boolean nullableHost) {
        if (nullableHost && leaves.stream().allMatch(leaf -> leaf.value() == null)) {
            return null;
        }
        Class<?> hostType = hostField.getType();
        Map<Field, List<DecodedLeaf>> nested = new LinkedHashMap<>();
        List<DecodedLeaf> direct = new ArrayList<>();
        for (DecodedLeaf leaf : leaves) {
            List<Field> path = leaf.property().embeddedHostPath();
            if (path.size() == depth + 1) {
                direct.add(leaf);
            } else {
                Field child = path.get(depth + 1);
                nested.computeIfAbsent(child, ignored -> new ArrayList<>()).add(leaf);
            }
        }
        Map<String, Object> values = new LinkedHashMap<>();
        for (DecodedLeaf leaf : direct) {
            values.put(leaf.property().field().getName(), leaf.value());
        }
        for (Map.Entry<Field, List<DecodedLeaf>> entry : nested.entrySet()) {
            values.put(entry.getKey().getName(), constructHost(entry.getKey(), depth + 1, entry.getValue(), true));
        }
        if (hostType.isRecord()) {
            return instantiateRecord(hostType, values, "record embeddable " + hostType.getName());
        }
        Object instance = instantiateMutable(hostType);
        for (DecodedLeaf leaf : direct) {
            writeMutableLeaf(instance, leaf.property(), leaf.value());
        }
        for (Map.Entry<Field, List<DecodedLeaf>> entry : nested.entrySet()) {
            setField(instance, entry.getKey(), values.get(entry.getKey().getName()), "nested embedded host");
        }
        return instance;
    }

    private static Object instantiateRecord(Class<?> type, Map<String, Object> values, String location) {
        RecordComponent[] components = type.getRecordComponents();
        Object[] arguments = new Object[components.length];
        for (int i = 0; i < components.length; i++) {
            RecordComponent component = components[i];
            if (!values.containsKey(component.getName())) {
                throw new IllegalStateException(location + " component '" + component.getName()
                        + "' has no persistent metadata; record embeddables cannot contain unmapped components");
            }
            Object value = values.get(component.getName());
            if (value == null && component.getType().isPrimitive()) {
                throw new IllegalStateException(location + " component '" + component.getName()
                        + "' is primitive " + component.getType().getName()
                        + " but the database value is NULL");
            }
            arguments[i] = value;
        }
        try {
            return canonicalConstructor(type).newInstance(arguments);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Cannot instantiate " + location + " through its canonical constructor",
                    exception);
        }
    }

    private static Constructor<?> canonicalConstructor(Class<?> type) {
        return RECORD_CONSTRUCTORS.computeIfAbsent(type, recordType -> {
            Class<?>[] parameterTypes = java.util.Arrays.stream(recordType.getRecordComponents())
                    .map(RecordComponent::getType).toArray(Class<?>[]::new);
            try {
                Constructor<?> constructor = recordType.getDeclaredConstructor(parameterTypes);
                constructor.setAccessible(true);
                return constructor;
            } catch (NoSuchMethodException exception) {
                throw new IllegalStateException("Record embeddable " + recordType.getName()
                        + " does not expose its canonical constructor", exception);
            }
        });
    }

    private static Object instantiateMutable(Class<?> type) {
        try {
            Constructor<?> constructor = type.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Embeddable type must expose a no-args constructor: " + type.getName(),
                    exception);
        }
    }

    private static void writeMutableLeaf(Object holder, PersistentProperty property, Object value) {
        if (property.propertyAccess()) {
            Method setter = property.propertyAccessSetter();
            if (setter == null) {
                throw new IllegalStateException("Cannot write immutable PROPERTY-access component "
                        + property.propertyName() + " outside canonical record construction");
            }
            try {
                setter.invoke(holder, value);
                return;
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException("Cannot write PROPERTY-access embedded component "
                        + property.propertyName(), exception);
            }
        }
        setField(holder, property.field(), value, "embedded component");
    }

    private static void setField(Object target, Field field, Object value, String label) {
        try {
            field.setAccessible(true);
            field.set(target, value);
        } catch (IllegalAccessException | IllegalArgumentException exception) {
            throw new IllegalStateException("Cannot write " + label + " "
                    + field.getDeclaringClass().getName() + "." + field.getName(), exception);
        }
    }

    record DecodedLeaf(PersistentProperty property, Object value) {
    }
}
