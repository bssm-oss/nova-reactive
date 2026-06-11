package io.nova.core;

import io.nova.metadata.EntityMetadata;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;

/**
 * {@code @PrePersist}, {@code @PostPersist}, {@code @PreUpdate}, {@code @PostUpdate}, {@code @PostLoad},
 * {@code @PreRemove}, {@code @PostRemove} 콜백을 entity 인스턴스에 대해 reflective하게 invoke한다.
 * 메서드 시그니처 검증과 {@code setAccessible(true)}는 metadata 단계에서 이미 처리되었으므로, 여기서는
 * 단순히 invoke만 수행한다.
 *
 * <p>각 phase는 entity의 declaration 순서대로 콜백을 호출한다. 콜백이 checked exception을 던지면
 * {@link InvocationTargetException}으로 감싸여 도착하므로, 원본 cause를 보존한 채
 * {@link IllegalStateException}으로 다시 던진다.
 */
final class EntityListenerInvoker {

    void invokePrePersist(Object entity, EntityMetadata<?> metadata) {
        invokeAll(entity, metadata.prePersistCallbacks(), "@PrePersist");
    }

    void invokePostPersist(Object entity, EntityMetadata<?> metadata) {
        invokeAll(entity, metadata.postPersistCallbacks(), "@PostPersist");
    }

    void invokePreUpdate(Object entity, EntityMetadata<?> metadata) {
        invokeAll(entity, metadata.preUpdateCallbacks(), "@PreUpdate");
    }

    void invokePostUpdate(Object entity, EntityMetadata<?> metadata) {
        invokeAll(entity, metadata.postUpdateCallbacks(), "@PostUpdate");
    }

    void invokePostLoad(Object entity, EntityMetadata<?> metadata) {
        invokeAll(entity, metadata.postLoadCallbacks(), "@PostLoad");
    }

    void invokePreRemove(Object entity, EntityMetadata<?> metadata) {
        invokeAll(entity, metadata.preRemoveCallbacks(), "@PreRemove");
    }

    void invokePostRemove(Object entity, EntityMetadata<?> metadata) {
        invokeAll(entity, metadata.postRemoveCallbacks(), "@PostRemove");
    }

    private void invokeAll(Object entity, List<Method> callbacks, String phase) {
        for (Method callback : callbacks) {
            try {
                callback.invoke(entity);
            } catch (InvocationTargetException exception) {
                Throwable cause = exception.getTargetException();
                throw new IllegalStateException(
                        phase + " callback " + describe(callback) + " threw " + cause.getClass().getName(),
                        cause);
            } catch (IllegalAccessException exception) {
                throw new IllegalStateException(
                        phase + " callback " + describe(callback) + " is not accessible", exception);
            }
        }
    }

    private static String describe(Method callback) {
        return callback.getDeclaringClass().getName() + "." + callback.getName();
    }
}
