package io.nova.metadata;

import java.util.List;

/**
 * {@code @EntityListeners}로 등록된 외부 리스너 클래스들의 콜백을 phase별로 묶은 holder. entity의 자체 콜백
 * (no-arg 메서드, {@link EntityMetadata}가 {@code List<Method>}로 보관)과 별도로, 각 phase마다 리스너 콜백을
 * declaration 순서로 들고 있다. 호출 순서는 JPA 규약을 따라 <b>리스너 콜백이 entity 자체 콜백보다 먼저</b>다.
 */
public record EntityListenerCallbacks(
        List<ListenerCallback> prePersist,
        List<ListenerCallback> postPersist,
        List<ListenerCallback> preUpdate,
        List<ListenerCallback> postUpdate,
        List<ListenerCallback> postLoad,
        List<ListenerCallback> preRemove,
        List<ListenerCallback> postRemove
) {
    public static final EntityListenerCallbacks EMPTY = new EntityListenerCallbacks(
            List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());

    public EntityListenerCallbacks {
        prePersist = List.copyOf(prePersist);
        postPersist = List.copyOf(postPersist);
        preUpdate = List.copyOf(preUpdate);
        postUpdate = List.copyOf(postUpdate);
        postLoad = List.copyOf(postLoad);
        preRemove = List.copyOf(preRemove);
        postRemove = List.copyOf(postRemove);
    }
}
