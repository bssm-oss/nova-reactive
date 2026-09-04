package io.nova.cache;

import io.nova.metadata.ElementCollectionInfo;
import io.nova.metadata.EntityMetadata;
import io.nova.metadata.EntityMetadataFactory;
import io.nova.metadata.PersistentAttributeAccess;
import io.nova.metadata.PersistentProperty;

import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Creates detached cache snapshots exclusively through mapped entity and value metadata. */
final class MappingAwareEntityGraphCopier {
    private final EntityMetadataFactory metadataFactory;

    MappingAwareEntityGraphCopier(EntityMetadataFactory metadataFactory) {
        this.metadataFactory = metadataFactory;
    }

    <T> T copy(T entity) {
        return entity == null ? null : cast(copyEntity(entity, new IdentityHashMap<>()));
    }

    List<Object> copyAll(List<?> entities) {
        IdentityHashMap<Object, Object> copies = new IdentityHashMap<>();
        List<Object> result = new ArrayList<>(entities.size());
        for (Object entity : entities) result.add(entity == null ? null : copyEntity(entity, copies));
        return result;
    }

    private Object copyEntity(Object source, IdentityHashMap<Object, Object> copies) {
        if (copies.containsKey(source)) return copies.get(source);
        EntityMetadata<Object> metadata = metadataFactory.getEntityMetadata(entityType(source));
        Object target = instantiate(metadata.entityType());
        copies.put(source, target);
        Map<EmbeddedHostKey, List<PersistentProperty>> embedded = new LinkedHashMap<>();
        for (PersistentProperty property : metadata.properties()) {
            if (property.embedded()) {
                PersistentAttributeAccess root = property.embeddedHostAccessPath().get(0);
                embedded.computeIfAbsent(EmbeddedHostKey.of(root), ignored -> new ArrayList<>()).add(property);
            } else {
                copyProperty(source, target, property, copies);
            }
        }
        for (List<PersistentProperty> properties : embedded.values()) {
            PersistentAttributeAccess root = properties.get(0).embeddedHostAccessPath().get(0);
            copyEmbedded(source, target, root, properties, copies);
        }
        return target;
    }

    private void copyProperty(Object source, Object target, PersistentProperty property,
            IdentityHashMap<Object, Object> copies) {
        if (property.manyToOne() || property.inverseToOne()) {
            Object related = property.readReferenceInstance(source);
            property.writeReferenceInstance(target, related == null ? null : copyEntity(related, copies));
        } else if (property.oneToMany() || property.manyToMany()) {
            property.writeCollection(target, copyEntityCollection(property.readReferenceInstance(source), copies));
        } else if (property.elementCollection()) {
            property.writeCollection(target, copyElementCollection(property.readReferenceInstance(source),
                    property.elementCollectionInfo(), copies));
        } else {
            Object value = property.read(source);
            property.write(target, copyScalar(property.toPropertyValue(property.toColumnValue(value)), copies));
        }
    }

    private void copyEmbedded(Object source, Object target, PersistentAttributeAccess root,
            List<PersistentProperty> properties, IdentityHashMap<Object, Object> copies) {
        root.write(target, copyEmbeddedHost(source, source, root, 0, properties, copies));
    }

    private Object copyEmbeddedHost(Object source, Object sourceHolder, PersistentAttributeAccess host, int depth,
            List<PersistentProperty> properties, IdentityHashMap<Object, Object> copies) {
        Object sourceHost = host.read(sourceHolder);
        if (sourceHost == null) return null;
        Map<EmbeddedHostKey, List<PersistentProperty>> nested = new LinkedHashMap<>();
        List<PersistentProperty> direct = new ArrayList<>();
        Map<String, Object> values = new LinkedHashMap<>();
        for (PersistentProperty property : properties) {
            List<PersistentAttributeAccess> path = property.embeddedHostAccessPath();
            if (path.size() == depth + 1) {
                direct.add(property);
                Object value = property.read(source);
                values.put(property.leafName(),
                        copyScalar(property.toPropertyValue(property.toColumnValue(value)), copies));
            } else {
                PersistentAttributeAccess child = path.get(depth + 1);
                nested.computeIfAbsent(EmbeddedHostKey.of(child), ignored -> new ArrayList<>()).add(property);
            }
        }
        for (List<PersistentProperty> childProperties : nested.values()) {
            PersistentAttributeAccess child = childProperties.get(0).embeddedHostAccessPath().get(depth + 1);
            values.put(child.name(), copyEmbeddedHost(source, sourceHost, child, depth + 1, childProperties, copies));
        }
        Object embedded;
        if (host.javaType().isRecord()) {
            embedded = constructRecord(host.javaType(), values, copies, sourceHost);
        } else {
            embedded = instantiate(host.javaType());
            copies.put(sourceHost, embedded);
            for (PersistentProperty property : direct)
                property.writeEmbeddedLeaf(embedded, values.get(property.leafName()));
            for (List<PersistentProperty> childProperties : nested.values()) {
                PersistentAttributeAccess child = childProperties.get(0).embeddedHostAccessPath().get(depth + 1);
                childProperties.get(0).writeEmbeddedHost(embedded, depth + 1, values.get(child.name()));
            }
        }
        return embedded;
    }

    private Object copyEntityCollection(Object source, IdentityHashMap<Object, Object> copies) {
        if (source == null) return null;
        if (source instanceof Map<?, ?> map) {
            Map<Object, Object> target = new LinkedHashMap<>();
            copies.put(source, target);
            for (Map.Entry<?, ?> entry : map.entrySet())
                target.put(copyScalar(entry.getKey(), copies), entry.getValue() == null ? null : copyEntity(entry.getValue(), copies));
            return target;
        }
        java.util.Collection<?> values = (java.util.Collection<?>) source;
        java.util.Collection<Object> target = source instanceof java.util.Set<?> ? new LinkedHashSet<>() : new ArrayList<>();
        copies.put(source, target);
        for (Object value : values) target.add(value == null ? null : copyEntity(value, copies));
        return target;
    }

    private Object copyElementCollection(Object source, ElementCollectionInfo info, IdentityHashMap<Object, Object> copies) {
        if (source == null) return null;
        if (copies.containsKey(source)) return copies.get(source);
        if (source instanceof Map<?, ?> map) {
            Map<Object, Object> target = new LinkedHashMap<>();
            copies.put(source, target);
            for (Map.Entry<?, ?> entry : map.entrySet()) target.put(copyElementKey(entry.getKey(), info, copies), copyElement(entry.getValue(), info, copies));
            return target;
        }
        java.util.Collection<?> values = (java.util.Collection<?>) source;
        java.util.Collection<Object> target = info.usesSet() ? new LinkedHashSet<>() : new ArrayList<>();
        copies.put(source, target);
        for (Object value : values) target.add(copyElement(value, info, copies));
        return target;
    }

    private Object copyElement(Object value, ElementCollectionInfo info, IdentityHashMap<Object, Object> copies) {
        if (value == null) return null;
        if (!info.embeddable()) return copyScalar(info.decodeElementValue(info.encodeElementValue(value)), copies);
        return copyEmbeddable(value, info.embeddableColumns(), copies);
    }

    private Object copyElementKey(Object value, ElementCollectionInfo info, IdentityHashMap<Object, Object> copies) {
        if (value == null) return null;
        ElementCollectionInfo.MapKeyInfo key = info.mapKey();
        if (key.entityKey()) return copyEntity(value, copies);
        if (key.embeddableKey()) return copyEmbeddable(value, key.embeddableKeyColumns(), copies);
        Object stored = key.keyConverter() == null ? value : key.keyConverter().write(value);
        return copyScalar(key.keyConverter() == null ? stored : key.keyConverter().read(stored), copies);
    }

    private Object copyEmbeddable(Object source, List<ElementCollectionInfo.EmbeddableColumn> columns,
            IdentityHashMap<Object, Object> copies) {
        if (copies.containsKey(source)) return copies.get(source);
        Class<?> type = source.getClass();
        if (type.isRecord()) {
            Map<String, Object> values = new LinkedHashMap<>();
            for (ElementCollectionInfo.EmbeddableColumn column : columns) {
                Object value = column.read(source);
                values.put(column.propertyName(), copyScalar(column.decode(column.encode(value)), copies));
            }
            return constructRecord(type, values, copies, source);
        }
        Object target = instantiate(type);
        copies.put(source, target);
        for (ElementCollectionInfo.EmbeddableColumn column : columns) {
            Object value = column.read(source);
            column.write(target, copyScalar(column.decode(column.encode(value)), copies));
        }
        return target;
    }

    private Object copyScalar(Object value, IdentityHashMap<Object, Object> copies) {
        if (value == null || immutable(value.getClass())) return value;
        if (value instanceof java.sql.Timestamp timestamp) {
            java.sql.Timestamp copy = new java.sql.Timestamp(timestamp.getTime());
            copy.setNanos(timestamp.getNanos());
            return copy;
        }
        if (value instanceof java.sql.Date date) return new java.sql.Date(date.getTime());
        if (value instanceof java.sql.Time time) return new java.sql.Time(time.getTime());
        if (value instanceof Date date) return new Date(date.getTime());
        if (value instanceof Calendar calendar) return (Calendar) calendar.clone();
        if (value instanceof byte[] bytes) return bytes.clone();
        if (value instanceof char[] chars) return chars.clone();
        if (value.getClass().isRecord()) return copyRecord(value, copies);
        // Supported mapped mutable values must be reconstructed by their converter; never reflect unmanaged state.
        return value;
    }

    private Object copyRecord(Object source, IdentityHashMap<Object, Object> copies) {
        if (copies.containsKey(source)) return copies.get(source);
        Map<String, Object> values = new LinkedHashMap<>();
        try {
            for (RecordComponent component : source.getClass().getRecordComponents())
                values.put(component.getName(), copyScalar(component.getAccessor().invoke(source), copies));
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Cannot read mapped record " + source.getClass().getName(), exception);
        }
        return constructRecord(source.getClass(), values, copies, source);
    }

    private Object constructRecord(Class<?> type, Map<String, Object> values, IdentityHashMap<Object, Object> copies, Object source) {
        try {
            RecordComponent[] components = type.getRecordComponents();
            Class<?>[] parameterTypes = new Class<?>[components.length];
            Object[] arguments = new Object[components.length];
            for (int i = 0; i < components.length; i++) {
                parameterTypes[i] = components[i].getType();
                arguments[i] = values.get(components[i].getName());
            }
            Constructor<?> constructor = type.getDeclaredConstructor(parameterTypes);
            constructor.setAccessible(true);
            Object target = constructor.newInstance(arguments);
            copies.put(source, target);
            return target;
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Cannot reconstruct mapped record " + type.getName(), exception);
        }
    }

    private static Object instantiate(Class<?> type) {
        try {
            Constructor<?> constructor = type.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Mapped type must expose a no-args constructor: " + type.getName(), exception);
        }
    }

    private static boolean immutable(Class<?> type) {
        return type.isPrimitive() || type.isEnum() || type == String.class || type == Boolean.class || type == Character.class
                || Number.class.isAssignableFrom(type) || type == java.util.UUID.class || type == Class.class
                || type.getPackageName().startsWith("java.time");
    }

    private record EmbeddedHostKey(Class<?> declaringType, String name) {
        private static EmbeddedHostKey of(PersistentAttributeAccess access) {
            return new EmbeddedHostKey(access.declaringType(), access.name());
        }
    }

    @SuppressWarnings("unchecked") private static <T> Class<T> entityType(Object entity) { return (Class<T>) entity.getClass(); }
    @SuppressWarnings("unchecked") private static <T> T cast(Object value) { return (T) value; }
}
