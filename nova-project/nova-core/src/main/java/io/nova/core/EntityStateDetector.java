package io.nova.core;

import io.nova.metadata.EntityMetadata;

public final class EntityStateDetector {
    public boolean isNew(Object entity, EntityMetadata<?> metadata) {
        Object value = metadata.idProperty().read(entity);
        return value == null;
    }
}
