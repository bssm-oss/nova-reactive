package io.nova.core;

import io.nova.metadata.ElementCollectionInfo;
import io.nova.metadata.PersistentAttributeAccess;
import io.nova.metadata.PersistentProperty;

import java.lang.reflect.Constructor;
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
        Map<HostKey, List<DecodedLeaf>> byRoot = new LinkedHashMap<>();
        for (DecodedLeaf leaf : leaves) {
            PersistentAttributeAccess root = leaf.property().embeddedHostAccessPath().get(0);
            byRoot.computeIfAbsent(HostKey.of(root), ignored -> new ArrayList<>()).add(leaf);
        }
        for (List<DecodedLeaf> rootLeaves : byRoot.values()) {
            PersistentAttributeAccess root = rootLeaves.get(0).property().embeddedHostAccessPath().get(0);
            Object value = constructHost(root, 0, rootLeaves, true);
            rootLeaves.get(0).property().writeEmbeddedHost(entity, 0, value);
        }
    }

    static Object instantiateCollectionRecord(
            Class<?> type, List<ElementCollectionInfo.EmbeddableColumn> columns, List<Object> values) {
        if (!type.isRecord()) {
            throw new IllegalArgumentException(type.getName() + " is not a record");
        }
        Map<String, Object> valuesByName = new LinkedHashMap<>();
        for (int i = 0; i < columns.size(); i++) {
            valuesByName.put(columns.get(i).propertyName(), values.get(i));
        }
        return instantiateRecord(type, valuesByName, "@ElementCollection record " + type.getName());
    }

    static Object readCollectionValue(Object value, ElementCollectionInfo.EmbeddableColumn column) {
        return column.read(value);
    }

    private static Object constructHost(
            PersistentAttributeAccess host, int depth, List<DecodedLeaf> leaves, boolean nullableHost) {
        if (nullableHost && leaves.stream().allMatch(leaf -> leaf.value() == null)) {
            return null;
        }
        Class<?> hostType = host.javaType();
        Map<HostKey, List<DecodedLeaf>> nested = new LinkedHashMap<>();
        List<DecodedLeaf> direct = new ArrayList<>();
        for (DecodedLeaf leaf : leaves) {
            List<PersistentAttributeAccess> path = leaf.property().embeddedHostAccessPath();
            if (path.size() == depth + 1) {
                direct.add(leaf);
            } else {
                PersistentAttributeAccess child = path.get(depth + 1);
                nested.computeIfAbsent(HostKey.of(child), ignored -> new ArrayList<>()).add(leaf);
            }
        }
        Map<String, Object> values = new LinkedHashMap<>();
        for (DecodedLeaf leaf : direct) {
            values.put(leaf.property().leafName(), leaf.value());
        }
        for (List<DecodedLeaf> childLeaves : nested.values()) {
            PersistentAttributeAccess child = childLeaves.get(0).property().embeddedHostAccessPath().get(depth + 1);
            values.put(child.name(), constructHost(child, depth + 1, childLeaves, true));
        }
        if (hostType.isRecord()) {
            return instantiateRecord(hostType, values, "record embeddable " + hostType.getName());
        }
        Object instance = instantiateMutable(hostType);
        for (DecodedLeaf leaf : direct) {
            writeMutableLeaf(instance, leaf.property(), leaf.value());
        }
        for (List<DecodedLeaf> childLeaves : nested.values()) {
            PersistentAttributeAccess child = childLeaves.get(0).property().embeddedHostAccessPath().get(depth + 1);
            childLeaves.get(0).property().writeEmbeddedHost(
                    instance, depth + 1, values.get(child.name()));
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
        property.writeEmbeddedLeaf(holder, value);
    }

    record DecodedLeaf(PersistentProperty property, Object value) {
    }

    private record HostKey(Class<?> declaringType, String name) {
        private static HostKey of(PersistentAttributeAccess access) {
            return new HostKey(access.declaringType(), access.name());
        }
    }
}
