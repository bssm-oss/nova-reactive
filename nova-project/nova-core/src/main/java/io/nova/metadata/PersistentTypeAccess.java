package io.nova.metadata;

import jakarta.persistence.AccessType;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable access plan for a managed type in one inherited access context. */
public final class PersistentTypeAccess {
    private final Class<?> type;
    private final AccessType accessType;
    private final List<PersistentAttributeAccess> attributes;
    private final Map<String, PersistentAttributeAccess> byName;

    PersistentTypeAccess(Class<?> type, AccessType accessType, List<PersistentAttributeAccess> attributes) {
        this.type = Objects.requireNonNull(type, "type");
        this.accessType = Objects.requireNonNull(accessType, "accessType");
        this.attributes = List.copyOf(attributes);
        Map<String, PersistentAttributeAccess> index = new LinkedHashMap<>();
        for (PersistentAttributeAccess attribute : attributes) {
            if (index.put(attribute.name(), attribute) != null) {
                throw new IllegalArgumentException(type.getName() + " declares duplicate persistent attribute " + attribute.name());
            }
        }
        this.byName = Map.copyOf(index);
    }

    public Class<?> type() { return type; }
    public AccessType accessType() { return accessType; }
    public List<PersistentAttributeAccess> attributes() { return attributes; }
    public PersistentAttributeAccess attribute(String name) { return byName.get(name); }
    public Collection<PersistentAttributeAccess> values() { return byName.values(); }
}
