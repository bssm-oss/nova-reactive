package io.nova.metadata;

import jakarta.persistence.Access;
import jakarta.persistence.AccessType;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Id;
import jakarta.persistence.Transient;

import java.beans.Introspector;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/** Resolves and caches the one selected annotation/state carrier for managed attributes. */
public final class PersistentAccessResolver {
    private final Map<Key, PersistentTypeAccess> cache = new ConcurrentHashMap<>();

    public PersistentTypeAccess resolve(Class<?> type) {
        return resolve(type, null);
    }

    public PersistentTypeAccess resolve(Class<?> type, AccessType inheritedAccess) {
        Objects.requireNonNull(type, "type");
        return cache.computeIfAbsent(new Key(type, inheritedAccess), key -> create(key.type, key.inheritedAccess));
    }

    private PersistentTypeAccess create(Class<?> type, AccessType inheritedAccess) {
        AccessType access = explicitAccess(type);
        if (access == null) {
            access = inheritedAccess != null ? inheritedAccess : hierarchyDefault(type);
        }
        List<Class<?>> hierarchy = hierarchy(type);
        Map<String, Field> fields = new LinkedHashMap<>();
        Map<String, Method> getters = new LinkedHashMap<>();
        Map<String, Method> setters = new HashMap<>();
        for (Class<?> current : hierarchy) {
            for (Field field : current.getDeclaredFields()) {
                if (candidateField(field)) fields.put(field.getName(), field);
            }
            Map<String, Method> localGetters = getters(current);
            for (Map.Entry<String, Method> entry : localGetters.entrySet()) getters.put(entry.getKey(), entry.getValue());
            setters.putAll(setters(current));
        }
        List<String> names = new ArrayList<>();
        names.addAll(fields.keySet());
        for (String name : getters.keySet()) if (!names.contains(name)) names.add(name);
        names.sort(String::compareTo);
        List<PersistentAttributeAccess> attributes = new ArrayList<>();
        for (String name : names) {
            Field field = fields.get(name);
            Method getter = getters.get(name);
            AccessType memberAccess = memberAccess(field, getter);
            AccessType selected = memberAccess == null ? access : memberAccess;
            if (selected == AccessType.FIELD) {
                if (field == null) {
                    if (getter != null && hasMappingAnnotation(getter)) {
                        throw new IllegalArgumentException(type.getName() + "." + name + " has mapping annotations on inactive PROPERTY member");
                    }
                    continue;
                }
                validateAccess(field, AccessType.FIELD);
                rejectInactive(field, getter, type, name);
                if (!field.isAnnotationPresent(Transient.class)) attributes.add(new PersistentAttributeAccess(name, field));
            } else {
                if (getter == null) {
                    if (field != null && hasMappingAnnotation(field)) {
                        throw new IllegalArgumentException(type.getName() + "." + name + " has mapping annotations on inactive FIELD member");
                    }
                    continue;
                }
                validateAccess(getter, AccessType.PROPERTY);
                rejectInactive(getter, field, type, name);
                if (!getter.isAnnotationPresent(Transient.class)) {
                    Method setter = setters.get(name);
                    if (setter != null && !setter.getParameterTypes()[0].equals(getter.getReturnType())) {
                        throw new IllegalArgumentException(type.getName() + "." + name + " getter/setter types are incompatible");
                    }
                    attributes.add(new PersistentAttributeAccess(name, getter, setter));
                }
            }
        }
        return new PersistentTypeAccess(type, access, attributes);
    }

    private static void rejectInactive(java.lang.reflect.AnnotatedElement selected,
            java.lang.reflect.AnnotatedElement inactive, Class<?> type, String name) {
        if (inactive != null && hasMappingAnnotation(inactive)) {
            Access access = inactive.getAnnotation(Access.class);
            if (access == null) {
                throw new IllegalArgumentException(type.getName() + "." + name + " has mapping annotations on inactive access member");
            }
        }
    }

    private static AccessType hierarchyDefault(Class<?> type) {
        AccessType found = null;
        for (Class<?> current : hierarchy(type)) {
            for (Field field : current.getDeclaredFields()) {
                if (field.isAnnotationPresent(Id.class) || field.isAnnotationPresent(EmbeddedId.class)) found = mergeIdentifierAccess(found, AccessType.FIELD, type);
            }
            for (Method method : current.getDeclaredMethods()) {
                if (method.isAnnotationPresent(Id.class) || method.isAnnotationPresent(EmbeddedId.class)) found = mergeIdentifierAccess(found, AccessType.PROPERTY, type);
            }
        }
        return found == null ? AccessType.FIELD : found;
    }

    private static AccessType mergeIdentifierAccess(AccessType existing, AccessType next, Class<?> type) {
        if (existing != null && existing != next) {
            throw new IllegalArgumentException(type.getName() + " mixes field and property identifier placement; declare @Access explicitly");
        }
        return next;
    }

    private static AccessType explicitAccess(Class<?> type) {
        Access annotation = type.getAnnotation(Access.class);
        return annotation == null ? null : annotation.value();
    }

    private static AccessType memberAccess(Field field, Method getter) {
        Access fieldAccess = field == null ? null : field.getAnnotation(Access.class);
        Access methodAccess = getter == null ? null : getter.getAnnotation(Access.class);
        if (fieldAccess != null && methodAccess != null && fieldAccess.value() != methodAccess.value()) {
            throw new IllegalArgumentException("Conflicting member @Access declarations");
        }
        return fieldAccess != null ? fieldAccess.value() : methodAccess == null ? null : methodAccess.value();
    }

    private static void validateAccess(java.lang.reflect.AnnotatedElement element, AccessType expected) {
        Access annotation = element.getAnnotation(Access.class);
        if (annotation != null && annotation.value() != expected) {
            throw new IllegalArgumentException("@Access(" + annotation.value() + ") is on the wrong persistent member");
        }
    }

    private static boolean candidateField(Field field) {
        int modifiers = field.getModifiers();
        return !field.isSynthetic() && !Modifier.isStatic(modifiers) && !Modifier.isTransient(modifiers);
    }

    private static Map<String, Method> getters(Class<?> type) {
        Map<String, Method> result = new HashMap<>();
        for (Method method : type.getDeclaredMethods()) {
            if (method.isBridge() || method.isSynthetic() || Modifier.isStatic(method.getModifiers()) || method.getParameterCount() != 0 || method.getReturnType() == Void.TYPE) continue;
            String name = getterName(method);
            if (name == null) continue;
            Method previous = result.put(name, method);
            if (previous != null) throw new IllegalArgumentException(type.getName() + " has ambiguous getter for " + name);
        }
        if (type.isRecord()) {
            for (RecordComponent component : type.getRecordComponents()) {
                Method accessor = component.getAccessor();
                Method previous = result.put(component.getName(), accessor);
                if (previous != null && previous != accessor) {
                    throw new IllegalArgumentException(type.getName() + " has ambiguous accessor for " + component.getName());
                }
            }
        }
        return result;
    }

    private static Map<String, Method> setters(Class<?> type) {
        Map<String, Method> result = new HashMap<>();
        for (Method method : type.getDeclaredMethods()) {
            if (method.isBridge() || method.isSynthetic() || Modifier.isStatic(method.getModifiers()) || method.getParameterCount() != 1 || method.getReturnType() != Void.TYPE || !method.getName().startsWith("set") || method.getName().length() == 3) continue;
            String name = Introspector.decapitalize(method.getName().substring(3));
            if (result.put(name, method) != null) throw new IllegalArgumentException(type.getName() + " has overloaded setter for " + name);
        }
        return result;
    }

    private static String getterName(Method method) {
        String name = method.getName();
        if (name.startsWith("get") && name.length() > 3) return Introspector.decapitalize(name.substring(3));
        if (name.startsWith("is") && name.length() > 2 && (method.getReturnType() == boolean.class || method.getReturnType() == Boolean.class)) return Introspector.decapitalize(name.substring(2));
        return null;
    }

    private static List<Class<?>> hierarchy(Class<?> type) {
        List<Class<?>> result = new ArrayList<>();
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) result.add(0, current);
        return result;
    }

    private static boolean hasMappingAnnotation(java.lang.reflect.AnnotatedElement element) {
        for (Annotation annotation : element.getAnnotations()) {
            String packageName = annotation.annotationType().getPackageName();
            if ((packageName.equals("jakarta.persistence") || packageName.startsWith("io.nova.annotation"))
                    && annotation.annotationType() != Access.class && annotation.annotationType() != Transient.class) return true;
        }
        return false;
    }

    private record Key(Class<?> type, AccessType inheritedAccess) { }
}
