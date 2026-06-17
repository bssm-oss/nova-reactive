package io.nova.core;

import io.nova.metadata.DefaultNamingStrategy;
import io.nova.metadata.EntityMetadata;
import io.nova.metadata.EntityMetadataFactory;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.ExcludeDefaultListeners;
import jakarta.persistence.ExcludeSuperclassListeners;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code @ExcludeDefaultListeners}, {@code @ExcludeSuperclassListeners}, 그리고 리스너 클래스 상속 체인의
 * 콜백 수집/호출 동작을 검증한다. 콜백 호출 추적이 필요하므로 fixture 리스너/엔티티를 테스트 클래스 내부
 * static class로 둔다(공유 {@link io.nova.support.fixtures.FixtureEntities}는 상태 누적을 피한다).
 */
class EntityListenerExclusionTest {

    private final EntityMetadataFactory factory = new EntityMetadataFactory(new DefaultNamingStrategy());
    private final EntityListenerInvoker invoker = new EntityListenerInvoker();

    static final List<String> events = new ArrayList<>();

    @BeforeEach
    void resetEvents() {
        events.clear();
    }

    // (a) @ExcludeDefaultListeners: entity 자체 콜백은 스킵하되 외부 리스너는 계속 호출된다.
    @Test
    void excludeDefaultListenersSkipsEntityCallbacksButKeepsExternalListeners() {
        EntityMetadata<EntityExcludingDefaults> metadata =
                factory.getEntityMetadata(EntityExcludingDefaults.class);

        assertTrue(metadata.excludeDefaultListeners(),
                "@ExcludeDefaultListeners must be reflected in metadata");

        invoker.invokePrePersist(new EntityExcludingDefaults(), metadata);

        assertEquals(List.of("recording:prePersist"), events,
                "external listener fires, entity's own @PrePersist is skipped");
    }

    // 회귀: @ExcludeDefaultListeners가 없으면 entity 콜백도 호출된다.
    @Test
    void withoutExcludeDefaultListenersEntityCallbackStillFires() {
        EntityMetadata<EntityKeepingDefaults> metadata =
                factory.getEntityMetadata(EntityKeepingDefaults.class);

        assertFalse(metadata.excludeDefaultListeners());

        invoker.invokePrePersist(new EntityKeepingDefaults(), metadata);

        assertEquals(List.of("recording:prePersist", "entity:prePersist"), events);
    }

    // (b) @ExcludeSuperclassListeners: 상위 host(@MappedSuperclass)의 리스너는 제외되고
    //     현재 클래스에 선언된 리스너만 유지된다.
    @Test
    void excludeSuperclassListenersDropsInheritedListeners() {
        EntityMetadata<ChildExcludingSuperListeners> metadata =
                factory.getEntityMetadata(ChildExcludingSuperListeners.class);

        // 오직 child가 선언한 RecordingListener의 콜백만 남는다(superclass의 SuperListener 제외).
        assertEquals(1, metadata.listenerCallbacks().prePersist().size());

        invoker.invokePrePersist(new ChildExcludingSuperListeners(), metadata);

        assertEquals(List.of("recording:prePersist", "entity:prePersist"), events);
    }

    // 회귀: @ExcludeSuperclassListeners가 없으면 superclass 리스너가 먼저, child 리스너가 다음에 발화한다.
    @Test
    void withoutExcludeSuperclassListenersInheritedListenersFireFirst() {
        EntityMetadata<ChildKeepingSuperListeners> metadata =
                factory.getEntityMetadata(ChildKeepingSuperListeners.class);

        assertEquals(2, metadata.listenerCallbacks().prePersist().size());

        invoker.invokePrePersist(new ChildKeepingSuperListeners(), metadata);

        // JPA: superclass 리스너 → child 리스너 → entity 콜백.
        assertEquals(
                List.of("super:prePersist", "recording:prePersist", "entity:prePersist"),
                events);
    }

    // (c) 리스너 클래스 상속: 리스너 클래스의 superclass에 선언된 콜백도 호출되고, override는 한 번만.
    @Test
    void listenerClassInheritsSuperclassCallbacks() {
        EntityMetadata<EntityWithInheritingListener> metadata =
                factory.getEntityMetadata(EntityWithInheritingListener.class);

        // base의 @PostLoad(상속) + base의 @PrePersist(override되어 child 버전 1개) 가 수집되어야 한다.
        assertEquals(1, metadata.listenerCallbacks().postLoad().size(),
                "inherited (non-overridden) @PostLoad from base listener is collected");
        assertEquals(1, metadata.listenerCallbacks().prePersist().size(),
                "overridden @PrePersist is collected exactly once (most-derived wins)");

        EntityWithInheritingListener entity = new EntityWithInheritingListener();
        invoker.invokePostLoad(entity, metadata);
        invoker.invokePrePersist(entity, metadata);

        // @PostLoad는 base 정의가 호출, @PrePersist는 child override가 호출(중복 없음).
        assertEquals(List.of("base:postLoad", "child:prePersist"), events);
    }

    // (d) 회귀: 리스너 상속 없이 단일 리스너 클래스의 콜백은 그대로 동작한다.
    @Test
    void plainListenerStillWorks() {
        EntityMetadata<EntityKeepingDefaults> metadata =
                factory.getEntityMetadata(EntityKeepingDefaults.class);

        invoker.invokePostLoad(new EntityKeepingDefaults(), metadata);

        assertEquals(List.of("recording:postLoad"), events);
    }

    // ---- fixtures ----

    static class RecordingListener {
        @PrePersist
        void onPrePersist(Object entity) {
            events.add("recording:prePersist");
        }

        @PostLoad
        void onPostLoad(Object entity) {
            events.add("recording:postLoad");
        }
    }

    static class SuperListener {
        @PrePersist
        void onPrePersist(Object entity) {
            events.add("super:prePersist");
        }
    }

    @Entity
    @Table(name = "excluding_defaults")
    @EntityListeners(RecordingListener.class)
    @ExcludeDefaultListeners
    static class EntityExcludingDefaults {
        @Id
        Long id;

        @PrePersist
        void onPrePersist() {
            events.add("entity:prePersist");
        }
    }

    @Entity
    @Table(name = "keeping_defaults")
    @EntityListeners(RecordingListener.class)
    static class EntityKeepingDefaults {
        @Id
        Long id;

        @PrePersist
        void onPrePersist() {
            events.add("entity:prePersist");
        }
    }

    @MappedSuperclass
    @EntityListeners(SuperListener.class)
    abstract static class BaseWithSuperListener {
        @Id
        Long id;

        @PrePersist
        void onPrePersist() {
            events.add("entity:prePersist");
        }
    }

    @Entity
    @Table(name = "child_excluding_super")
    @EntityListeners(RecordingListener.class)
    @ExcludeSuperclassListeners
    static class ChildExcludingSuperListeners extends BaseWithSuperListener {
    }

    @Entity
    @Table(name = "child_keeping_super")
    @EntityListeners(RecordingListener.class)
    static class ChildKeepingSuperListeners extends BaseWithSuperListener {
    }

    /** 리스너 클래스의 base: @PostLoad는 상속만 되고, @PrePersist는 child가 override한다. */
    static class BaseInheritingListener {
        @PrePersist
        void onPrePersist(Object entity) {
            events.add("base:prePersist");
        }

        @PostLoad
        void onPostLoad(Object entity) {
            events.add("base:postLoad");
        }
    }

    static class DerivedInheritingListener extends BaseInheritingListener {
        @Override
        @PrePersist
        void onPrePersist(Object entity) {
            events.add("child:prePersist");
        }
    }

    @Entity
    @Table(name = "inheriting_listener")
    @EntityListeners(DerivedInheritingListener.class)
    static class EntityWithInheritingListener {
        @Id
        Long id;
    }
}
