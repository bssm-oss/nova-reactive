package io.nova.core;

import io.nova.metadata.EntityMetadata;
import io.nova.metadata.ListenerCallback;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;

/**
 * {@code @PrePersist}, {@code @PostPersist}, {@code @PreUpdate}, {@code @PostUpdate}, {@code @PostLoad},
 * {@code @PreRemove}, {@code @PostRemove} 콜백을 entity 인스턴스에 대해 reflective하게 invoke한다.
 * 메서드 시그니처 검증과 {@code setAccessible(true)}는 metadata 단계에서 이미 처리되었으므로, 여기서는
 * 단순히 invoke만 수행한다.
 *
 * <p>각 phase는 먼저 {@code @EntityListeners} 외부 리스너 콜백을(리스너/메서드 선언 순서대로), 이어서
 * entity 자체 콜백을(declaration 순서대로) 호출한다 — JPA 규약(리스너 우선). 콜백이 checked exception을
 * 던지면 {@link InvocationTargetException}으로 감싸여 도착하므로, 원본 cause를 보존한 채
 * {@link IllegalStateException}으로 다시 던진다.
 *
 * <p>JPA의 {@code @ExcludeDefaultListeners}는 XML(orm.xml)로 선언된 <em>default entity listener</em>만
 * 제외하며 entity 자체 콜백에는 영향이 없다. Nova는 default listener 메커니즘 자체가 없으므로 이 어노테이션은
 * <strong>인식되지만 no-op</strong>이다 — entity 자체 콜백을 스킵하지 않는다(스킵하면 사용자의
 * audit/validation 로직이 조용히 사라지는 JPA 비호환 동작이 된다). superclass 리스너 제외는 별도
 * 어노테이션 {@code @ExcludeSuperclassListeners}가 담당한다.
 */
final class EntityListenerInvoker {

    void invokePrePersist(Object entity, EntityMetadata<?> metadata) {
        invokeListeners(entity, metadata.listenerCallbacks().prePersist(), "@PrePersist");
        invokeAll(entity, metadata.prePersistCallbacks(), "@PrePersist");
    }

    void invokePostPersist(Object entity, EntityMetadata<?> metadata) {
        invokeListeners(entity, metadata.listenerCallbacks().postPersist(), "@PostPersist");
        invokeAll(entity, metadata.postPersistCallbacks(), "@PostPersist");
    }

    void invokePreUpdate(Object entity, EntityMetadata<?> metadata) {
        invokeListeners(entity, metadata.listenerCallbacks().preUpdate(), "@PreUpdate");
        invokeAll(entity, metadata.preUpdateCallbacks(), "@PreUpdate");
    }

    void invokePostUpdate(Object entity, EntityMetadata<?> metadata) {
        invokeListeners(entity, metadata.listenerCallbacks().postUpdate(), "@PostUpdate");
        invokeAll(entity, metadata.postUpdateCallbacks(), "@PostUpdate");
    }

    void invokePostLoad(Object entity, EntityMetadata<?> metadata) {
        invokeListeners(entity, metadata.listenerCallbacks().postLoad(), "@PostLoad");
        invokeAll(entity, metadata.postLoadCallbacks(), "@PostLoad");
    }

    void invokePreRemove(Object entity, EntityMetadata<?> metadata) {
        invokeListeners(entity, metadata.listenerCallbacks().preRemove(), "@PreRemove");
        invokeAll(entity, metadata.preRemoveCallbacks(), "@PreRemove");
    }

    void invokePostRemove(Object entity, EntityMetadata<?> metadata) {
        invokeListeners(entity, metadata.listenerCallbacks().postRemove(), "@PostRemove");
        invokeAll(entity, metadata.postRemoveCallbacks(), "@PostRemove");
    }

    private void invokeListeners(Object entity, List<ListenerCallback> callbacks, String phase) {
        for (ListenerCallback callback : callbacks) {
            try {
                callback.method().invoke(callback.listener(), entity);
            } catch (InvocationTargetException exception) {
                Throwable cause = exception.getTargetException();
                throw new IllegalStateException(
                        phase + " listener callback " + describe(callback.method())
                                + " threw " + cause.getClass().getName(),
                        cause);
            } catch (IllegalAccessException exception) {
                throw new IllegalStateException(
                        phase + " listener callback " + describe(callback.method()) + " is not accessible",
                        exception);
            }
        }
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
