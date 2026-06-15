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
    private final String schema;
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
    /**
     * id로 표시된 property들을 declaration 순서로 보관한다. 단일 {@code @Id}는 1개, {@code @EmbeddedId}로
     * 펼쳐진 복합키는 컴포넌트 수만큼이다. {@link #properties}에서 {@link PersistentProperty#id()} 필터로
     * 파생되므로 생성자 시그니처를 바꾸지 않고 자동으로 채워진다.
     */
    private final List<PersistentProperty> idProperties;
    /**
     * 관계/값 컬렉션 property 뷰는 생성 시 한 번 계산해 캐시한다. {@link #properties}는 생성 후 불변이므로
     * 매 호출 {@code stream().filter().toList()}를 반복할 이유가 없다 — findById/findAll/save 핫패스에서
     * {@link #hasRelationProperties()}가 호출될 때마다 리스트 5개를 새로 할당하던 오버헤드를 제거한다.
     */
    private final List<PersistentProperty> manyToOneProperties;
    private final List<PersistentProperty> oneToManyProperties;
    private final List<PersistentProperty> oneToOneInverseProperties;
    private final List<PersistentProperty> manyToManyProperties;
    private final List<PersistentProperty> elementCollectionProperties;
    private final boolean hasRelationProperties;
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
    private final InheritanceInfo inheritance;

    public EntityMetadata(
            Class<T> entityType,
            String entityName,
            String tableName,
            String schema,
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
        this(entityType, entityName, tableName, schema, properties, idProperty,
                prePersistCallbacks, postPersistCallbacks, preUpdateCallbacks, postUpdateCallbacks,
                postLoadCallbacks, preRemoveCallbacks, postRemoveCallbacks, indexes, uniqueConstraints,
                InheritanceInfo.NONE);
    }

    public EntityMetadata(
            Class<T> entityType,
            String entityName,
            String tableName,
            String schema,
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
            List<UniqueConstraintDefinition> uniqueConstraints,
            InheritanceInfo inheritance
    ) {
        this.entityType = entityType;
        this.entityName = entityName;
        this.tableName = tableName;
        this.schema = schema == null ? "" : schema;
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
            if (!property.oneToMany() && !property.inverseToOne()
                    && !property.manyToMany() && !property.elementCollection()) {
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
        this.idProperties = this.properties.stream().filter(PersistentProperty::id).toList();
        this.manyToOneProperties = this.properties.stream().filter(PersistentProperty::manyToOne).toList();
        this.oneToManyProperties = this.properties.stream().filter(PersistentProperty::oneToMany).toList();
        this.oneToOneInverseProperties = this.properties.stream().filter(PersistentProperty::inverseToOne).toList();
        this.manyToManyProperties = this.properties.stream().filter(PersistentProperty::manyToMany).toList();
        this.elementCollectionProperties = this.properties.stream().filter(PersistentProperty::elementCollection).toList();
        this.hasRelationProperties = !manyToOneProperties.isEmpty()
                || !oneToManyProperties.isEmpty()
                || !oneToOneInverseProperties.isEmpty()
                || !manyToManyProperties.isEmpty()
                || !elementCollectionProperties.isEmpty();
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
        this.inheritance = inheritance == null ? InheritanceInfo.NONE : inheritance;
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
     * {@code @Table(schema=...)}로 지정된 스키마 이름. 미지정이면 빈 문자열이며, 이 경우 테이블 참조는
     * 스키마 한정 없이 렌더링된다.
     */
    public String schema() {
        return schema;
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

    /**
     * 대표 id property를 반환한다. 단일 {@code @Id}는 그 property를, {@code @EmbeddedId} 복합키는 첫 번째
     * 컴포넌트를 반환한다. id <em>전체</em>를 다뤄야 하는 WHERE 절/바인딩 경로에서는 반드시
     * {@link #idProperties()}를 사용해야 한다 — 복합키에서 이 메서드만 쓰면 첫 컬럼만 보게 된다.
     * generation 전략 판정({@link PersistentProperty#generationType()})처럼 복합키에서 무의미한(=null)
     * 경로는 이 대표 값으로 안전하게 no-op 처리된다.
     */
    public PersistentProperty idProperty() {
        return idProperty;
    }

    /**
     * id로 표시된 모든 property. 단일 {@code @Id}는 1개, {@code @EmbeddedId} 복합키는 컴포넌트 전체다.
     * selectById/deleteById/update의 WHERE 절은 이 리스트를 순회해 {@code c1 = ? and c2 = ?} 형태로 렌더한다.
     */
    public List<PersistentProperty> idProperties() {
        return idProperties;
    }

    /**
     * {@code @EmbeddedId}로 펼쳐진 복합키(컴포넌트 2개 이상)이면 {@code true}.
     */
    public boolean hasCompositeId() {
        return idProperties.size() > 1;
    }

    /**
     * findById/deleteById에 전달하는 형태의 id 값을 entity에서 읽어 반환한다. 단일 키는 스칼라 id 값,
     * {@code @EmbeddedId}는 {@code @Embeddable} holder 객체, {@code @IdClass}는 entity의 각 {@code @Id}
     * 값으로 채운 IdClass 인스턴스를 반환한다. 복합키 entity의 insert/update 분기(존재 확인)와 에러
     * 메시지에서 사용한다.
     */
    public Object readIdValue(Object entity) {
        if (!hasCompositeId()) {
            return idProperty.read(entity);
        }
        if (idProperties.get(0).embedded()) {
            // @EmbeddedId: holder 객체를 그대로 반환한다.
            return idProperties.get(0).readHostHolder(entity);
        }
        // @IdClass: 별도 IdClass 인스턴스를 만들어 entity의 @Id 값들을 같은 이름 필드에 채운다.
        Class<?> idClass = requireIdClass();
        Object instance = instantiateIdClass(idClass);
        for (PersistentProperty idProperty : idProperties) {
            java.lang.reflect.Field target = idClassField(idClass, idProperty.propertyName());
            try {
                target.set(instance, idProperty.read(entity));
            } catch (IllegalAccessException exception) {
                throw new IllegalStateException("Cannot write @IdClass field " + target.getName(), exception);
            }
        }
        return instance;
    }

    /**
     * id 값 객체에서 주어진 id property가 매핑된 컬럼에 바인딩할 값을 꺼낸다. 단일 키는 객체 자체가 값,
     * {@code @EmbeddedId}는 holder의 leaf 필드, {@code @IdClass}는 IdClass 인스턴스의 같은 이름 필드다.
     * selectById/deleteById 렌더링에서 id 객체를 컬럼별 값으로 분해할 때 사용한다.
     */
    public Object idColumnValue(PersistentProperty idProperty, Object idObject) {
        if (!hasCompositeId()) {
            return idObject;
        }
        if (idProperty.embedded()) {
            return idProperty.readFromIdHolder(idObject);
        }
        java.lang.reflect.Field source = idClassField(idObject.getClass(), idProperty.propertyName());
        try {
            return source.get(idObject);
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("Cannot read @IdClass field " + source.getName(), exception);
        }
    }

    private Class<?> requireIdClass() {
        jakarta.persistence.IdClass annotation = entityType.getAnnotation(jakarta.persistence.IdClass.class);
        if (annotation == null) {
            throw new IllegalStateException(entityType.getName() + " has no @IdClass");
        }
        return annotation.value();
    }

    private static Object instantiateIdClass(Class<?> idClass) {
        try {
            java.lang.reflect.Constructor<?> constructor = idClass.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(
                    "@IdClass type must expose a no-args constructor: " + idClass.getName(), exception);
        }
    }

    private static java.lang.reflect.Field idClassField(Class<?> idClass, String fieldName) {
        try {
            java.lang.reflect.Field field = idClass.getDeclaredField(fieldName);
            field.setAccessible(true);
            return field;
        } catch (NoSuchFieldException exception) {
            throw new IllegalStateException(
                    "@IdClass " + idClass.getName() + " has no field '" + fieldName + "'", exception);
        }
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
                .filter(PersistentProperty::insertable)
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
            // SEQUENCE/UUID는 애플리케이션이 INSERT 직전에 id를 채운다. TABLE은 metadata 빌드 단계에서
            // 이미 거부되므로 여기 도달하지 않지만, switch 포괄성을 위해 app-supplied 쪽으로 분류한다.
            case SEQUENCE, UUID, TABLE -> false;
        };
    }

    public List<PersistentProperty> updatableProperties() {
        return columnMappedProperties.stream()
                .filter(property -> !property.id())
                .filter(PersistentProperty::updatable)
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
     * {@code @ManyToOne} owning property들. 생성 시 1회 계산된 immutable 캐시를 반환한다({@link #properties}는
     * 불변이라 매 호출 재계산할 이유가 없다).
     */
    public List<PersistentProperty> manyToOneProperties() {
        return manyToOneProperties;
    }

    /**
     * {@code @OneToMany} inverse property들(캐시).
     */
    public List<PersistentProperty> oneToManyProperties() {
        return oneToManyProperties;
    }

    /**
     * inverse-side {@code @OneToOne}({@code mappedBy}) property들(캐시).
     * owning-side {@code @OneToOne}은 {@link #manyToOneProperties()}에 포함된다(@ManyToOne과 동일 모델링).
     */
    public List<PersistentProperty> oneToOneInverseProperties() {
        return oneToOneInverseProperties;
    }

    /**
     * {@code @ManyToMany} property들(owning + inverse, 캐시). 컬럼이 없는 marker라 link table hydration과
     * save 시 link 동기화에서만 사용된다.
     */
    public List<PersistentProperty> manyToManyProperties() {
        return manyToManyProperties;
    }

    /**
     * {@code @ElementCollection} 값 컬렉션 property들(캐시). collection table hydration과 save 시 값 동기화에서만 사용된다.
     */
    public List<PersistentProperty> elementCollectionProperties() {
        return elementCollectionProperties;
    }

    /**
     * 관계/값 컬렉션 property가 하나라도 있으면 {@code true}. annotation-driven 자동 hydration의 진입 가드로
     * 사용된다 — 관계가 없는 entity는 기존 zero-overhead findById/findAll 경로를 그대로 거친다. 생성 시 1회
     * 계산된 boolean을 반환한다(핫패스에서 매 호출 5종 stream 재계산하던 오버헤드 제거).
     */
    public boolean hasRelationProperties() {
        return hasRelationProperties;
    }

    /**
     * SINGLE_TABLE 상속 discriminator 메타데이터. 상속에 참여하지 않으면 {@link InheritanceInfo#NONE}.
     */
    public InheritanceInfo inheritance() {
        return inheritance;
    }

    /**
     * 이 엔티티가 SINGLE_TABLE 상속 계층의 멤버이면 {@code true}.
     */
    public boolean hasInheritance() {
        return inheritance.present();
    }

    /**
     * 이 엔티티가 계층 루트(서브타입 제약 WHERE 없이 다형 조회되는 타입)이면 {@code true}.
     */
    public boolean isInheritanceRoot() {
        return inheritance.present() && inheritance.isRoot();
    }
}
