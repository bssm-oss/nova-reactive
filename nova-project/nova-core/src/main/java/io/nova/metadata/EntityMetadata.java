package io.nova.metadata;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class EntityMetadata<T> {
    private final Class<T> entityType;
    private final String entityName;
    private final String tableName;
    /**
     * 선언된 모든 property를 declaration 순서로 보관한다. {@code @OneToMany} 같이 컬럼이 없는 marker-only
     * property도 포함된다. {@link #properties()}가 이 리스트를 그대로 반환한다.
     */
    private final List<PersistentProperty> properties;
    /**
     * column이 매핑된 property만 추린 view. SELECT/INSERT/UPDATE 렌더링, row 디코딩, schema 생성 등 컬럼이
     * 필요한 경로에서 명시적으로 {@link #columnMappedProperties()}로 가져와 쓴다. 결과는 immutable이며
     * {@link #properties}와 declaration 순서를 공유한다.
     */
    private final List<PersistentProperty> columnMappedProperties;
    private final Map<String, PersistentProperty> propertiesByName;
    private final PersistentProperty idProperty;
    private final PersistentProperty createdAtProperty;
    private final PersistentProperty updatedAtProperty;
    private final PersistentProperty softDeleteProperty;
    private final PersistentProperty versionProperty;
    private final List<Method> prePersistCallbacks;
    private final List<Method> postPersistCallbacks;
    private final List<Method> preUpdateCallbacks;
    private final List<Method> postUpdateCallbacks;
    private final List<Method> postLoadCallbacks;
    private final List<Method> preRemoveCallbacks;
    private final List<Method> postRemoveCallbacks;
    private final List<IndexDefinition> indexes;
    private final List<UniqueConstraintDefinition> uniqueConstraints;

    public EntityMetadata(
            Class<T> entityType,
            String entityName,
            String tableName,
            List<PersistentProperty> properties,
            PersistentProperty idProperty,
            List<Method> prePersistCallbacks,
            List<Method> postPersistCallbacks,
            List<Method> preUpdateCallbacks,
            List<Method> postUpdateCallbacks,
            List<Method> postLoadCallbacks,
            List<Method> preRemoveCallbacks,
            List<Method> postRemoveCallbacks,
            List<IndexDefinition> indexes,
            List<UniqueConstraintDefinition> uniqueConstraints
    ) {
        this.entityType = entityType;
        this.entityName = entityName;
        this.tableName = tableName;
        this.properties = List.copyOf(properties);
        LinkedHashMap<String, PersistentProperty> index = new LinkedHashMap<>();
        java.util.ArrayList<PersistentProperty> columnMapped = new java.util.ArrayList<>(this.properties.size());
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
            if (!property.oneToMany()) {
                columnMapped.add(property);
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
        this.columnMappedProperties = List.copyOf(columnMapped);
        this.propertiesByName = Collections.unmodifiableMap(index);
        this.idProperty = idProperty;
        this.createdAtProperty = createdAt;
        this.updatedAtProperty = updatedAt;
        this.softDeleteProperty = softDelete;
        this.versionProperty = version;
        this.prePersistCallbacks = List.copyOf(prePersistCallbacks);
        this.postPersistCallbacks = List.copyOf(postPersistCallbacks);
        this.preUpdateCallbacks = List.copyOf(preUpdateCallbacks);
        this.postUpdateCallbacks = List.copyOf(postUpdateCallbacks);
        this.postLoadCallbacks = List.copyOf(postLoadCallbacks);
        this.preRemoveCallbacks = List.copyOf(preRemoveCallbacks);
        this.postRemoveCallbacks = List.copyOf(postRemoveCallbacks);
        this.indexes = List.copyOf(indexes);
        this.uniqueConstraints = List.copyOf(uniqueConstraints);
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

    /**
     * 선언된 모든 property를 declaration 순서로 반환한다. {@code @OneToMany} 같이 컬럼이 없는 marker-only
     * property도 포함된다. SQL 렌더링/row 디코딩/schema 생성 등 컬럼이 필요한 경로에서는
     * {@link #columnMappedProperties()}를 명시적으로 호출하라.
     */
    public List<PersistentProperty> properties() {
        return properties;
    }

    /**
     * 컬럼이 매핑된 property만 반환한다 — SELECT/INSERT/UPDATE 렌더링, row 디코딩, schema 생성 등에서
     * 사용한다. {@code @OneToMany}처럼 inverse side로만 정의되어 컬럼이 없는 marker는 결과에서 제외된다.
     */
    public List<PersistentProperty> columnMappedProperties() {
        return columnMappedProperties;
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
        return columnMappedProperties.stream()
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
            case SEQUENCE, UUID -> false;
        };
    }

    public List<PersistentProperty> updatableProperties() {
        return columnMappedProperties.stream()
                .filter(property -> !property.id())
                .toList();
    }

    public Optional<PersistentProperty> createdAtProperty() {
        return Optional.ofNullable(createdAtProperty);
    }

    public Optional<PersistentProperty> updatedAtProperty() {
        return Optional.ofNullable(updatedAtProperty);
    }

    /**
     * {@code @PrePersist} 콜백 메서드들을 declaration 순서대로 반환한다.
     */
    public List<Method> prePersistCallbacks() {
        return prePersistCallbacks;
    }

    /**
     * {@code @PostPersist} 콜백 메서드들을 declaration 순서대로 반환한다.
     */
    public List<Method> postPersistCallbacks() {
        return postPersistCallbacks;
    }

    /**
     * {@code @PreUpdate} 콜백 메서드들을 declaration 순서대로 반환한다.
     */
    public List<Method> preUpdateCallbacks() {
        return preUpdateCallbacks;
    }

    /**
     * {@code @PostUpdate} 콜백 메서드들을 declaration 순서대로 반환한다.
     */
    public List<Method> postUpdateCallbacks() {
        return postUpdateCallbacks;
    }

    /**
     * {@code @PostLoad} 콜백 메서드들을 declaration 순서대로 반환한다.
     */
    public List<Method> postLoadCallbacks() {
        return postLoadCallbacks;
    }

    /**
     * {@code @PreRemove} 콜백 메서드들을 declaration 순서대로 반환한다.
     */
    public List<Method> preRemoveCallbacks() {
        return preRemoveCallbacks;
    }

    /**
     * {@code @PostRemove} 콜백 메서드들을 declaration 순서대로 반환한다.
     */
    public List<Method> postRemoveCallbacks() {
        return postRemoveCallbacks;
    }

    /**
     * 타입 레벨 {@code @Index}로 선언된 secondary index 정의 목록.
     */
    public List<IndexDefinition> indexes() {
        return indexes;
    }

    /**
     * 타입 레벨 {@code @UniqueConstraint}로 선언된 unique constraint 정의 목록.
     */
    public List<UniqueConstraintDefinition> uniqueConstraints() {
        return uniqueConstraints;
    }

    /**
     * {@code @ManyToOne} owning property들. 캐시하지 않고 매 호출마다 properties stream을 흘려 새 리스트를
     * 만들어 새 marker가 추가될 때 derived getter가 자동으로 따라가게 한다.
     */
    public List<PersistentProperty> manyToOneProperties() {
        return properties.stream().filter(PersistentProperty::manyToOne).toList();
    }

    /**
     * {@code @OneToMany} inverse property들. 캐시하지 않는다(동일 사유).
     */
    public List<PersistentProperty> oneToManyProperties() {
        return properties.stream().filter(PersistentProperty::oneToMany).toList();
    }

    /**
     * {@code @ManyToOne} 또는 {@code @OneToMany} 중 하나라도 존재하면 {@code true}. annotation-driven 자동
     * hydration의 진입 가드로 사용된다 — 관계가 없는 entity는 기존 zero-overhead findById/findAll 경로를
     * 그대로 거친다.
     */
    public boolean hasRelationProperties() {
        return !manyToOneProperties().isEmpty() || !oneToManyProperties().isEmpty();
    }
}
