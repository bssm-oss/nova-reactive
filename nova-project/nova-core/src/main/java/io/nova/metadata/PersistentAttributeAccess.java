package io.nova.metadata;

import jakarta.persistence.AccessType;

import java.lang.annotation.Annotation;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.Objects;

/** Immutable, selected access path for one persistent attribute. */
public final class PersistentAttributeAccess {
    private final String name;
    private final Class<?> declaringType;
    private final Class<?> javaType;
    private final Type genericType;
    private final AccessType accessType;
    private final AnnotatedElement annotatedElement;
    private final Field field;
    private final Method getter;
    private final Method setter;
    private final MethodHandle reader;
    private final MethodHandle writer;

    PersistentAttributeAccess(String name, Field field) {
        this.name = Objects.requireNonNull(name, "name");
        this.field = Objects.requireNonNull(field, "field");
        this.declaringType = field.getDeclaringClass();
        this.javaType = field.getType();
        this.genericType = field.getGenericType();
        this.accessType = AccessType.FIELD;
        this.annotatedElement = field;
        this.getter = null;
        this.setter = null;
        try {
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(declaringType, MethodHandles.lookup());
            this.reader = lookup.unreflectGetter(field);
            this.writer = java.lang.reflect.Modifier.isFinal(field.getModifiers()) ? null : lookup.unreflectSetter(field);
        } catch (IllegalAccessException e) {
            throw inaccessible(declaringType, name, e);
        }
    }

    PersistentAttributeAccess(String name, Method getter, Method setter) {
        this(name, getter, setter, null);
    }

    PersistentAttributeAccess(String name, Method getter, Method setter, Field backingField) {
        this.name = Objects.requireNonNull(name, "name");
        this.getter = Objects.requireNonNull(getter, "getter");
        this.field = backingField;
        this.declaringType = getter.getDeclaringClass();
        this.javaType = getter.getReturnType();
        this.genericType = getter.getGenericReturnType();
        this.accessType = AccessType.PROPERTY;
        this.annotatedElement = getter;
        this.setter = setter;
        try {
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(declaringType, MethodHandles.lookup());
            this.reader = lookup.unreflect(getter);
            this.writer = setter == null ? null : lookup.unreflect(setter);
        } catch (IllegalAccessException e) {
            throw inaccessible(declaringType, name, e);
        }
    }

    private static IllegalArgumentException inaccessible(Class<?> type, String name, Exception cause) {
        return new IllegalArgumentException("Cannot access persistent attribute " + type.getName() + "." + name
                + "; open its package to io.nova.metadata", cause);
    }

    public String name() { return name; }
    public Class<?> declaringType() { return declaringType; }
    public Class<?> javaType() { return javaType; }
    public Type genericType() { return genericType; }
    public AccessType accessType() { return accessType; }
    public Field field() { return field; }
    public Method getter() { return getter; }
    public Method setter() { return setter; }
    public boolean writable() { return writer != null; }

    public <A extends Annotation> A annotation(Class<A> annotationType) {
        return annotatedElement.getAnnotation(annotationType);
    }

    public boolean isAnnotationPresent(Class<? extends Annotation> annotationType) {
        return annotatedElement.isAnnotationPresent(annotationType);
    }

    public Annotation[] annotations() { return annotatedElement.getAnnotations(); }

    public <A extends Annotation> A[] annotationsByType(Class<A> annotationType) {
        return annotatedElement.getAnnotationsByType(annotationType);
    }

    public Object read(Object instance) {
        try {
            return reader.invoke(instance);
        } catch (Throwable e) {
            throw new IllegalStateException("Cannot read persistent attribute " + declaringType.getName() + "." + name, e);
        }
    }

    public void write(Object instance, Object value) {
        if (writer == null) {
            throw new IllegalStateException("Persistent attribute " + declaringType.getName() + "." + name + " is read-only");
        }
        try {
            writer.invoke(instance, value);
        } catch (Throwable e) {
            throw new IllegalStateException("Cannot write persistent attribute " + declaringType.getName() + "." + name, e);
        }
    }
}
