package io.nova.metadata;

import java.util.List;
import java.util.Optional;

public final class EntityMetadata<T> {
    private final Class<T> entityType;
    private final String entityName;
    private final String tableName;
    private final List<PersistentProperty> properties;
    private final PersistentProperty idProperty;
    private final PersistentProperty createdAtProperty;
    private final PersistentProperty updatedAtProperty;
    private final PersistentProperty softDeleteProperty;

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
        this.idProperty = idProperty;
        this.createdAtProperty = this.properties.stream()
                .filter(PersistentProperty::createdAt)
                .findFirst()
                .orElse(null);
        this.updatedAtProperty = this.properties.stream()
                .filter(PersistentProperty::updatedAt)
                .findFirst()
                .orElse(null);
        this.softDeleteProperty = this.properties.stream()
                .filter(PersistentProperty::softDelete)
                .findFirst()
                .orElse(null);
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

    public PersistentProperty idProperty() {
        return idProperty;
    }

    public Optional<PersistentProperty> softDeleteProperty() {
        return Optional.ofNullable(softDeleteProperty);
    }

    public List<PersistentProperty> insertableProperties() {
        return properties.stream()
                .filter(property -> !property.id() || !property.generated())
                .toList();
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
