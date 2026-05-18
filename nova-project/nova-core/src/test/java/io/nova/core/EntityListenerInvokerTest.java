package io.nova.core;

import io.nova.metadata.DefaultNamingStrategy;
import io.nova.metadata.EntityMetadata;
import io.nova.metadata.EntityMetadataFactory;
import io.nova.support.fixtures.FixtureEntities.CallbackThrowingEntity;
import io.nova.support.fixtures.FixtureEntities.EntityWithCallbacks;
import io.nova.support.fixtures.FixtureEntities.SampleAccount;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntityListenerInvokerTest {
    private final EntityMetadataFactory factory = new EntityMetadataFactory(new DefaultNamingStrategy());
    private final EntityListenerInvoker invoker = new EntityListenerInvoker();

    @BeforeEach
    void resetCallbackState() {
        EntityWithCallbacks.reset();
    }

    @Test
    void invokesPrePersistCallbackAndIncrementsCounter() {
        EntityMetadata<EntityWithCallbacks> metadata = factory.getEntityMetadata(EntityWithCallbacks.class);
        EntityWithCallbacks entity = new EntityWithCallbacks(null, "  user@nova.io  ");

        invoker.invokePrePersist(entity, metadata);

        assertEquals(1, EntityWithCallbacks.prePersistCount.get());
        // callback이 email을 trim()해 mutation이 entity에 반영되어야 한다.
        assertEquals("user@nova.io", entity.getEmail());
    }

    @Test
    void invokesPreUpdateCallback() {
        EntityMetadata<EntityWithCallbacks> metadata = factory.getEntityMetadata(EntityWithCallbacks.class);
        EntityWithCallbacks entity = new EntityWithCallbacks(7L, "USER@NOVA.IO");

        invoker.invokePreUpdate(entity, metadata);

        assertEquals(1, EntityWithCallbacks.preUpdateCount.get());
        assertEquals("user@nova.io", entity.getEmail());
    }

    @Test
    void invokesPostLoadCallback() {
        EntityMetadata<EntityWithCallbacks> metadata = factory.getEntityMetadata(EntityWithCallbacks.class);
        EntityWithCallbacks entity = new EntityWithCallbacks(7L, "user@nova.io");

        invoker.invokePostLoad(entity, metadata);

        assertEquals(1, EntityWithCallbacks.postLoadCount.get());
    }

    @Test
    void invokesPreRemoveCallback() {
        EntityMetadata<EntityWithCallbacks> metadata = factory.getEntityMetadata(EntityWithCallbacks.class);
        EntityWithCallbacks entity = new EntityWithCallbacks(7L, "user@nova.io");

        invoker.invokePreRemove(entity, metadata);

        assertEquals(1, EntityWithCallbacks.preRemoveCount.get());
    }

    @Test
    void isNoOpForEntitiesWithoutCallbacks() {
        EntityMetadata<SampleAccount> metadata = factory.getEntityMetadata(SampleAccount.class);
        SampleAccount entity = new SampleAccount(7L, "x@nova.io", true);

        invoker.invokePrePersist(entity, metadata);
        invoker.invokePreUpdate(entity, metadata);
        invoker.invokePostLoad(entity, metadata);
        invoker.invokePreRemove(entity, metadata);

        // 어떤 호출도 예외를 던지지 않아야 한다.
        assertTrue(metadata.prePersistCallbacks().isEmpty());
    }

    @Test
    void wrapsCallbackExceptionInIllegalStateExceptionWithOriginalCause() {
        EntityMetadata<CallbackThrowingEntity> metadata = factory.getEntityMetadata(CallbackThrowingEntity.class);
        CallbackThrowingEntity entity = new CallbackThrowingEntity(1L);

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> invoker.invokePrePersist(entity, metadata)
        );

        assertTrue(exception.getMessage().contains("@PrePersist"));
        assertTrue(exception.getMessage().contains("CallbackThrowingEntity"));
        assertSame(IllegalArgumentException.class, exception.getCause().getClass());
        assertEquals("callback boom", exception.getCause().getMessage());
    }
}
