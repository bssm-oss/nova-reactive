package io.nova.metadata;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class EntityMetadata<T> {
    private final Class<T> entityType;
    private final String entityName;
    private final String tableName;
    private final List<PersistentProperty> properties;
    private final Map<String, PersistentProperty> propertiesByName;
    private final PersistentProperty idProperty;
    private final PersistentProperty createdAtProperty;
    private final PersistentProperty updatedAtProperty;
    private final PersistentProperty softDeleteProperty;
    private final PersistentProperty versionProperty;

    public EntityMetadata(
            Class<T> entityType,
            String entityName,
            String tableName,
            List<PersistentProperty> properties,
            PersistentProperty idProperty
    ) {
        this.entityType = entityType;
        this.entityName = entityName;
        this.tableName = tableName;
        this.properties = List.copyOf(properties);
        LinkedHashMap<String, PersistentProperty> index = new LinkedHashMap<>();
        PersistentProperty createdAt = null;
        PersistentProperty updatedAt = null;
        PersistentProperty softDelete = null;
        PersistentProperty version = null;
        for (PersistentProperty property : this.properties) {
            PersistentProperty previous = index.put(property.propertyName(), property);
            if (previous != null) {
                throw new IllegalArgumentException(
                        entityType.getName() + " declares duplicate property name " + property.propertyName());
            }
            if (createdAt == null && property.createdAt()) {
                createdAt = property;
            }
            if (updatedAt == null && property.updatedAt()) {
                updatedAt = property;
            }
            if (softDelete == null && property.softDelete()) {
                softDelete = property;
            }
            if (version == null && property.version()) {
                version = property;
            }
        }
        this.propertiesByName = Collections.unmodifiableMap(index);
        this.idProperty = idProperty;
        this.createdAtProperty = createdAt;
        this.updatedAtProperty = updatedAt;
        this.softDeleteProperty = softDelete;
        this.versionProperty = version;
    }

    public Class<T> entityType() {
        return entityType;
    }

    public String entityName() {
        return entityName;
    }

    public String tableName() {
        return tableName;
    }

    public List<PersistentProperty> properties() {
        return properties;
    }

    /**
     * property 이름으로 {@link PersistentProperty}를 O(1)에 조회한다. 미존재 시 빈 {@link Optional}.
     */
    public Optional<PersistentProperty> findProperty(String propertyName) {
        return Optional.ofNullable(propertiesByName.get(propertyName));
    }

    public PersistentProperty idProperty() {
        return idProperty;
    }

    public Optional<PersistentProperty> softDeleteProperty() {
        return Optional.ofNullable(softDeleteProperty);
    }

    public Optional<PersistentProperty> versionProperty() {
        return Optional.ofNullable(versionProperty);
    }

    public List<PersistentProperty> insertableProperties() {
        return properties.stream()
                .filter(property -> !property.id() || !isDatabaseGeneratedId(property))
                .toList();
    }

    /**
     * INSERT 절에서 id 컬럼을 제외해야 하는지(=DB가 직접 채워주는 전략인지) 판단한다.
     * SEQUENCE와 UUID는 애플리케이션 측이 INSERT 직전에 id를 미리 할당하므로 INSERT에 포함된다.
     */
    public static boolean isDatabaseGeneratedId(PersistentProperty property) {
        if (!property.generated()) {
            return false;
        }
        return switch (property.generationType()) {
            case IDENTITY, AUTO -> true;
            case SEQUENCE, UUID, NONE -> false;
        };
    }

    public List<PersistentProperty> updatableProperties() {
        return properties.stream()
                .filter(property -> !property.id())
                .toList();
    }

    public Optional<PersistentProperty> createdAtProperty() {
        return Optional.ofNullable(createdAtProperty);
    }

    public Optional<PersistentProperty> updatedAtProperty() {
        return Optional.ofNullable(updatedAtProperty);
    }
}
