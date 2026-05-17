package io.nova.metadata;

import java.util.List;

public final class EntityMetadata<T> {
    private final Class<T> entityType;
    private final String entityName;
    private final String tableName;
    private final List<PersistentProperty> properties;
    private final PersistentProperty idProperty;

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
}
