package io.nova.cache;

import io.nova.metadata.EntityMetadata;
import io.nova.metadata.EntityMetadataFactory;
import io.nova.metadata.PersistentAttributeAccess;
import io.nova.metadata.PersistentProperty;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Creates detached cache snapshots using the mapped entity state and associations. */
final class MappingAwareEntityGraphCopier {

    private final EntityMetadataFactory metadataFactory;

    MappingAwareEntityGraphCopier(EntityMetadataFactory metadataFactory) {
        this.metadataFactory = metadataFactory;
    }

    <T> T copy(T entity) {
        if (entity == null) {
            return null;
        }
        return cast(copyEntity(entity, new IdentityHashMap<>()));
    }

    List<Object> copyAll(List<?> entities) {
        IdentityHashMap<Object, Object> copies = new IdentityHashMap<>();
        List<Object> result = new ArrayList<>(entities.size());
        for (Object entity : entities) {
            result.add(entity == null ? null : copyEntity(entity, copies));
        }
        return result;
    }

    private Object copyEntity(Object source, IdentityHashMap<Object, Object> copies) {
        Object existing = copies.get(source);
        if (existing != null) {
            return existing;
        }
        EntityMetadata<Object> metadata = metadataFactory.getEntityMetadata(entityType(source));
        Object target = instantiate(metadata.entityType());
        copies.put(source, target);
        for (PersistentProperty property : metadata.properties()) {
            copyProperty(source, target, property, copies);
        }
        return target;
    }

    private void copyProperty(
            Object source, Object target, PersistentProperty property, IdentityHashMap<Object, Object> copies) {
        if (property.embedded()) {
            copyEmbeddedHost(source, target, property, copies);
            return;
        }
        if (property.isRelation()) {
            Object value = property.readReferenceInstance(source);
            if (property.manyToOne() || property.inverseToOne()) {
                property.writeReferenceInstance(target, value == null ? null : copyEntity(value, copies));
            } else {
                property.writeCollection(target, copyValue(value, copies));
            }
            return;
        }
        if (property.elementCollection()) {
            property.writeCollection(target, copyValue(property.readReferenceInstance(source), copies));
            return;
        }
        Object value = property.read(source);
        // Round-trip through the registered converter so converted values use the same reconstruction
        // path as row hydration rather than sharing a mutable domain object with the cached graph.
        property.write(target, copyValue(property.toPropertyValue(property.toColumnValue(value)), copies));
    }

    private void copyEmbeddedHost(
            Object source, Object target, PersistentProperty property, IdentityHashMap<Object, Object> copies) {
        List<PersistentAttributeAccess> path = property.embeddedHostAccessPath();
        if (path.isEmpty()) {
            return;
        }
        PersistentAttributeAccess host = path.get(0);
        Object original = host.read(source);
        Object copied = copyValue(original, copies);
        if (copies.get(original) != copied || host.read(target) != copied) {
            host.write(target, copied);
        }
    }

    private Object copyValue(Object source, IdentityHashMap<Object, Object> copies) {
        if (source == null || isImmutable(source.getClass())) {
            return source;
        }
        Object existing = copies.get(source);
        if (existing != null) {
            return existing;
        }
        if (source instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            copies.put(source, copy);
            for (Object value : list) {
                copy.add(copyValue(value, copies));
            }
            return copy;
        }
        if (source instanceof java.util.Set<?> set) {
            java.util.Set<Object> copy = new LinkedHashSet<>(set.size());
            copies.put(source, copy);
            for (Object value : set) {
                copy.add(copyValue(value, copies));
            }
            return copy;
        }
        if (source instanceof Map<?, ?> map) {
            Map<Object, Object> copy = new LinkedHashMap<>(map.size());
            copies.put(source, copy);
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                copy.put(copyValue(entry.getKey(), copies), copyValue(entry.getValue(), copies));
            }
            return copy;
        }
        if (source.getClass().isArray()) {
            int length = Array.getLength(source);
            Object copy = Array.newInstance(source.getClass().getComponentType(), length);
            copies.put(source, copy);
            for (int index = 0; index < length; index++) {
                Array.set(copy, index, copyValue(Array.get(source, index), copies));
            }
            return copy;
        }
        if (source.getClass().isRecord()) {
            return copyRecord(source, copies);
        }
        return copyMutableValue(source, copies);
    }

    private Object copyRecord(Object source, IdentityHashMap<Object, Object> copies) {
        Class<?> type = source.getClass();
        RecordComponent[] components = type.getRecordComponents();
        Object[] values = new Object[components.length];
        try {
            for (int index = 0; index < components.length; index++) {
                values[index] = copyValue(components[index].getAccessor().invoke(source), copies);
            }
            Constructor<?> constructor = type.getDeclaredConstructor(
                    java.util.Arrays.stream(components).map(RecordComponent::getType).toArray(Class<?>[]::new));
            constructor.setAccessible(true);
            Object copy = constructor.newInstance(values);
            copies.put(source, copy);
            return copy;
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Cannot reconstruct mapped record " + type.getName(), exception);
        }
    }

    private Object copyMutableValue(Object source, IdentityHashMap<Object, Object> copies) {
        Object target = instantiate(source.getClass());
        copies.put(source, target);
        for (Class<?> type = source.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || Modifier.isTransient(field.getModifiers())) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    field.set(target, copyValue(field.get(source), copies));
                } catch (IllegalAccessException exception) {
                    throw new IllegalStateException("Cannot copy mapped value " + type.getName(), exception);
                }
            }
        }
        return target;
    }

    private static Object instantiate(Class<?> type) {
        try {
            Constructor<?> constructor = type.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Cached entity/value type must expose a no-args constructor: "
                    + type.getName(), exception);
        }
    }

    private static boolean isImmutable(Class<?> type) {
        return type.isPrimitive() || type.isEnum() || type == String.class || type == Boolean.class
                || type == Character.class || Number.class.isAssignableFrom(type) || type == java.util.UUID.class
                || type.getPackageName().startsWith("java.time") || type == Class.class;
    }

    @SuppressWarnings("unchecked")
    private static <T> Class<T> entityType(Object entity) {
        return (Class<T>) entity.getClass();
    }

    @SuppressWarnings("unchecked")
    private static <T> T cast(Object value) {
        return (T) value;
    }
}
