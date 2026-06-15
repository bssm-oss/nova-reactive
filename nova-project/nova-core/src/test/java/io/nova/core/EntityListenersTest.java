package io.nova.core;

import io.nova.metadata.DefaultNamingStrategy;
import io.nova.metadata.EntityMetadata;
import io.nova.metadata.EntityMetadataFactory;
import io.nova.support.fixtures.FixtureEntities.AuditedEntity;
import io.nova.support.fixtures.FixtureEntities.AuditingListener;
import io.nova.support.fixtures.FixtureEntities.EntityWithBadListener;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntityListenersTest {
    private final EntityMetadataFactory factory = new EntityMetadataFactory(new DefaultNamingStrategy());
    private final EntityListenerInvoker invoker = new EntityListenerInvoker();

    @BeforeEach
    void resetListenerState() {
        AuditingListener.reset();
    }

    @Test
    void collectsListenerCallbacksFromEntityListenersAnnotation() {
        EntityMetadata<AuditedEntity> metadata = factory.getEntityMetadata(AuditedEntity.class);

        // 두 리스너의 @PrePersist가 선언 순서대로 수집되어야 한다(AuditingListener, SecondListener).
        assertEquals(2, metadata.listenerCallbacks().prePersist().size());
        assertEquals(1, metadata.listenerCallbacks().postLoad().size());
    }

    @Test
    void invokesListenerCallbacksBeforeEntityCallbacks() {
        EntityMetadata<AuditedEntity> metadata = factory.getEntityMetadata(AuditedEntity.class);
        AuditedEntity entity = new AuditedEntity(null, "nova");

        invoker.invokePrePersist(entity, metadata);

        // JPA 규약: 리스너 콜백(선언 순서) → entity 자체 콜백 순.
        assertEquals(
                List.of("listener:prePersist", "second:prePersist", "entity:prePersist"),
                AuditingListener.events);
        // 리스너가 entity 필드를 mutate했고 그 변경이 보였다.
        assertEquals("listener-was-here", entity.getAudit());
    }

    @Test
    void invokesListenerForEachPhase() {
        EntityMetadata<AuditedEntity> metadata = factory.getEntityMetadata(AuditedEntity.class);
        AuditedEntity entity = new AuditedEntity(7L, "nova");

        invoker.invokePostPersist(entity, metadata);
        invoker.invokePreUpdate(entity, metadata);
        invoker.invokePostUpdate(entity, metadata);
        invoker.invokePostLoad(entity, metadata);
        invoker.invokePreRemove(entity, metadata);
        invoker.invokePostRemove(entity, metadata);

        assertTrue(AuditingListener.events.contains("listener:postPersist"));
        assertTrue(AuditingListener.events.contains("listener:preUpdate"));
        assertTrue(AuditingListener.events.contains("listener:postUpdate"));
        assertTrue(AuditingListener.events.contains("listener:postLoad"));
        assertTrue(AuditingListener.events.contains("listener:preRemove"));
        assertTrue(AuditingListener.events.contains("listener:postRemove"));
    }

    @Test
    void rejectsListenerCallbackWithWrongArity() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> factory.getEntityMetadata(EntityWithBadListener.class));

        assertTrue(exception.getMessage().contains("@PrePersist"));
        assertTrue(exception.getMessage().contains("single argument"));
    }
}
