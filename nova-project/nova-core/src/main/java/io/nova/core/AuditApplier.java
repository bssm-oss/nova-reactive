package io.nova.core;

import io.nova.metadata.EntityMetadata;
import io.nova.metadata.PersistentProperty;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * {@code @CreatedAt}, {@code @UpdatedAt} 필드에 현재 시각을 자동으로 채운다. insert 시 두 필드 모두
 * 대상이며 {@code @CreatedAt}은 entity에 이미 값이 있으면 보존한다. update 시에는 {@code @UpdatedAt}만
 * 대상이며 항상 덮어쓴다.
 */
final class AuditApplier {
    private final Clock clock;

    AuditApplier(Clock clock) {
        this.clock = clock;
    }

    void applyOnInsert(Object entity, EntityMetadata<?> metadata) {
        metadata.createdAtProperty().ifPresent(property -> {
            if (property.read(entity) == null) {
                property.write(entity, now(property.javaType()));
            }
        });
        metadata.updatedAtProperty().ifPresent(property ->
                property.write(entity, now(property.javaType())));
    }

    void applyOnUpdate(Object entity, EntityMetadata<?> metadata) {
        metadata.updatedAtProperty().ifPresent(property ->
                property.write(entity, now(property.javaType())));
    }

    Optional<String> updatedAtPropertyName(EntityMetadata<?> metadata) {
        return metadata.updatedAtProperty().map(PersistentProperty::propertyName);
    }

    /**
     * Updater 경로용. {@code @UpdatedAt}이 metadata에 있고 사용자가 명시적으로 같은 field를 지정하지
     * 않았다면, 현재 시각을 fieldValues에 자동 추가한다. 사용자가 명시적으로 set한 값은 보존한다.
     */
    void applyOnUpdaterFields(java.util.LinkedHashMap<String, Object> fieldValues, EntityMetadata<?> metadata) {
        metadata.updatedAtProperty().ifPresent(property -> {
            if (!fieldValues.containsKey(property.propertyName())) {
                fieldValues.put(property.propertyName(), now(property.javaType()));
            }
        });
    }

    private Object now(Class<?> javaType) {
        if (javaType == Instant.class) {
            return clock.instant();
        }
        if (javaType == LocalDateTime.class) {
            return LocalDateTime.now(clock);
        }
        if (javaType == OffsetDateTime.class) {
            return OffsetDateTime.now(clock);
        }
        throw new IllegalStateException("Unsupported audit type passed metadata validation: " + javaType);
    }
}
