package io.nova.metadata;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import io.nova.annotation.CreatedAt;
import jakarta.persistence.Convert;
import jakarta.persistence.DiscriminatorColumn;
import jakarta.persistence.DiscriminatorType;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Embedded;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Inheritance;
import jakarta.persistence.InheritanceType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
import io.nova.annotation.Json;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.PostRemove;
import jakarta.persistence.PostUpdate;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreRemove;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Transient;
import io.nova.annotation.SoftDelete;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import io.nova.annotation.UpdatedAt;
import jakarta.persistence.Version;
import io.nova.convert.AttributeConverter;
import io.nova.convert.EnumOrdinalConverter;
import io.nova.convert.EnumStringConverter;
import io.nova.convert.JpaAttributeConverterAdapter;
import io.nova.convert.JsonAttributeConverter;
import io.nova.json.JsonCodec;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Nova 매핑 어노테이션이 선언된 엔티티 클래스의 리플렉션 메타데이터를 생성하고 캐시한다.
 */
public final class EntityMetadataFactory {
    private static final Set<Class<?>> SUPPORTED_AUDIT_TYPES =
            Set.of(Instant.class, LocalDateTime.class, OffsetDateTime.class);

    private static final Set<Class<?>> SUPPORTED_SOFT_DELETE_TYPES =
            Set.of(Instant.class, LocalDateTime.class, OffsetDateTime.class);

    private static final Set<Class<?>> SUPPORTED_VERSION_TYPES =
            Set.of(Long.class, Integer.class, Short.class);

    private static final Set<Class<?>> SUPPORTED_UUID_ID_TYPES =
            Set.of(UUID.class, String.class);

    /**
     * SEQUENCE generator 이름이 SQL 식별자 형태를 따르도록 강제하는 정규식이다.
     * dialect가 {@code "'" + name + "'"} 같이 직접 concat할 가능성을 차단하기 위해
     * 따옴표, 세미콜론, 공백 등 식별자 외 문자는 모두 거부한다.
     */
    private static final Pattern SEQUENCE_GENERATOR_NAME_PATTERN =
            Pattern.compile("^[A-Za-z_][A-Za-z0-9_$.]*$");

    private final NamingStrategy namingStrategy;
    private final JsonCodec jsonCodec;
    private final Map<Class<?>, EntityMetadata<?>> cache = new ConcurrentHashMap<>();
    private final Map<Class<?>, AttributeConverter<?, ?>> converters = new ConcurrentHashMap<>();
    /**
     * SINGLE_TABLE 상속 계층 레지스트리. root 클래스 → (discriminator 값 → 구체 서브타입 클래스).
     * 각 구체 멤버의 메타데이터가 빌드될 때 자기 자신을 등록한다 — JPA persistence-unit이 모든 엔티티를
     * 부트스트랩 시 알고 있는 것과 같은 방식으로, root 다형 조회 전에 전 서브타입 메타데이터가 빌드돼 있어야
     * 한다(Spring starter의 entity-packages eager preload가 이를 보장한다).
     */
    private final Map<Class<?>, Map<String, Class<?>>> hierarchies = new ConcurrentHashMap<>();
    /**
     * root 클래스 → 전 서브타입 컬럼을 union한 single-table 병합 메타데이터 캐시. select-list/DDL에서
     * 한 테이블이 모든 서브타입 컬럼을 담도록 만들 때 사용한다.
     */
    private final Map<Class<?>, EntityMetadata<?>> mergedHierarchyCache = new ConcurrentHashMap<>();

    /**
     * {@link JsonCodec} 없이 factory를 만든다 — {@code @Json} 필드가 없는 엔티티만 다룰 때 사용한다.
     * {@code @Json} 필드가 발견되면 {@link JsonCodec#unconfigured()}가 변환 시점에
     * {@link IllegalStateException}을 던진다.
     */
    public EntityMetadataFactory(NamingStrategy namingStrategy) {
        this(namingStrategy, JsonCodec.unconfigured());
    }

    /**
     * 주어진 {@link JsonCodec}을 {@code @Json} 필드 변환에 사용하는 factory를 만든다.
     */
    public EntityMetadataFactory(NamingStrategy namingStrategy, JsonCodec jsonCodec) {
        this.namingStrategy = namingStrategy;
        this.jsonCodec = jsonCodec;
    }

    /**
     * 프로퍼티 타입용 converter를 등록해 컬럼 값과 프로퍼티 값 사이의 변환에 사용한다.
     */
    public <X, Y> void registerConverter(Class<X> propertyType, AttributeConverter<X, Y> converter) {
        converters.put(propertyType, converter);
    }

    /**
     * 엔티티 타입의 메타데이터를 반환하며, 없으면 처음 접근 시 생성해 캐시한다.
     */
    @SuppressWarnings("unchecked")
    public <T> EntityMetadata<T> getEntityMetadata(Class<T> entityType) {
        EntityMetadata<?> cached = cache.get(entityType);
        if (cached != null) {
            return (EntityMetadata<T>) cached;
        }
        EntityMetadata<T> created = createMetadata(entityType);
        cache.put(entityType, created);
        return created;
    }

    /**
     * SINGLE_TABLE 상속 루트의 모든 서브타입 컬럼을 union한 병합 메타데이터를 반환한다. 단일 테이블이
     * 모든 서브타입 컬럼을 담도록 select-list와 CREATE TABLE을 만들 때 사용한다. 서브타입 전용 컬럼은
     * 다른 서브타입 row에서 비어 있어야 하므로 nullable로 낮춘다. 루트가 아니거나 상속이 아니면 입력
     * 메타데이터를 그대로 돌려준다.
     *
     * <p>레지스트리에 등록된 서브타입만 union에 포함되므로, 다형 조회 전에 전 서브타입 메타데이터가
     * 빌드돼 있어야 한다(Spring starter의 entity-packages eager preload가 보장).
     */
    public EntityMetadata<?> mergedHierarchyMetadata(Class<?> rootClass) {
        EntityMetadata<?> cached = mergedHierarchyCache.get(rootClass);
        if (cached != null) {
            return cached;
        }
        EntityMetadata<?> merged = buildMergedHierarchyMetadata(getEntityMetadata(rootClass));
        mergedHierarchyCache.put(rootClass, merged);
        return merged;
    }

    private <T> EntityMetadata<T> buildMergedHierarchyMetadata(EntityMetadata<T> rootMeta) {
        if (!rootMeta.isInheritanceRoot()) {
            return rootMeta;
        }
        LinkedHashMap<String, PersistentProperty> union = new LinkedHashMap<>();
        for (PersistentProperty property : rootMeta.columnMappedProperties()) {
            union.put(property.columnName(), property);
        }
        Map<String, Class<?>> members = hierarchies.getOrDefault(rootMeta.entityType(), Map.of());
        for (Class<?> member : members.values()) {
            if (member == rootMeta.entityType()) {
                continue;
            }
            for (PersistentProperty property : getEntityMetadata(member).columnMappedProperties()) {
                // 서브타입 전용 컬럼만 추가하고 nullable로 낮춘다. 루트가 이미 가진 컬럼은 건너뛴다.
                union.putIfAbsent(property.columnName(), property.withNullable(true));
            }
        }
        return new EntityMetadata<>(
                rootMeta.entityType(),
                rootMeta.entityName(),
                rootMeta.tableName(),
                rootMeta.schema(),
                new ArrayList<>(union.values()),
                rootMeta.idProperty(),
                rootMeta.prePersistCallbacks(),
                rootMeta.postPersistCallbacks(),
                rootMeta.preUpdateCallbacks(),
                rootMeta.postUpdateCallbacks(),
                rootMeta.postLoadCallbacks(),
                rootMeta.preRemoveCallbacks(),
                rootMeta.postRemoveCallbacks(),
                rootMeta.indexes(),
                rootMeta.uniqueConstraints(),
                rootMeta.inheritance()
        );
    }

    /**
     * 루트 메타데이터와 row에서 읽은 discriminator 값으로 구체 서브타입 메타데이터를 해석한다. 매칭되는
     * 서브타입이 없으면 명확한 에러를 던진다 — 보통 해당 서브타입 메타데이터가 아직 빌드되지 않은 경우다.
     */
    public EntityMetadata<?> resolveSubtype(EntityMetadata<?> rootMetadata, Object rawDiscriminatorValue) {
        InheritanceInfo info = rootMetadata.inheritance();
        Map<String, Class<?>> members = hierarchies.getOrDefault(info.root(), Map.of());
        String key = rawDiscriminatorValue == null ? null : rawDiscriminatorValue.toString().trim();
        Class<?> concrete = key == null ? null : members.get(key);
        if (concrete != null) {
            return getEntityMetadata(concrete);
        }
        if (info.discriminatorValue().equals(key)) {
            return rootMetadata;
        }
        throw new IllegalStateException(
                "No @DiscriminatorValue '" + key + "' is registered for hierarchy "
                        + info.root().getName() + "; known values: " + members.keySet()
                        + ". Ensure every subtype's metadata is built before polymorphic queries"
                        + " (Spring resolves this automatically via entity-packages scanning).");
    }

    private <T> EntityMetadata<T> createMetadata(Class<T> entityType) {
        Entity entity = entityType.getAnnotation(Entity.class);
        if (entity == null) {
            throw new IllegalArgumentException(entityType.getName() + " is not annotated with @Entity");
        }

        String entityName = entity.name().isBlank() ? entityType.getSimpleName() : entity.name();
        // SINGLE_TABLE 상속: 테이블/스키마/인덱스는 계층 루트(@Inheritance 또는 최상위 @Entity)에서 가져온다.
        InheritanceInfo inheritance = resolveInheritance(entityType, entityName);
        Class<?> tableSource = inheritance.present() ? inheritance.root() : entityType;
        Table table = tableSource.getAnnotation(Table.class);
        String tableName = table != null && !table.name().isBlank() ? table.name() : namingStrategy.tableName(tableSource);

        List<PersistentProperty> properties = new ArrayList<>();
        PersistentProperty idProperty = null;
        PersistentProperty createdAtProperty = null;
        PersistentProperty updatedAtProperty = null;
        PersistentProperty softDeleteProperty = null;
        PersistentProperty versionProperty = null;
        for (Field field : mappedFields(entityType)) {
            if (isNotPersistable(field)) {
                continue;
            }
            rejectIncompatibleRelationAnnotations(entityType, field);
            if (field.isAnnotationPresent(OneToMany.class)) {
                // OneToMany는 parent 테이블 컬럼이 없는 marker-only property — column uniqueness 검증에서 제외된다.
                properties.add(createOneToManyProperty(entityType, field));
                continue;
            }
            if (field.isAnnotationPresent(ManyToOne.class)) {
                properties.add(createManyToOneProperty(entityType, field));
                continue;
            }
            if (field.isAnnotationPresent(OneToOne.class)) {
                // owning(@JoinColumn FK)은 컬럼이 있고, inverse(mappedBy)는 컬럼이 없는 마커다.
                properties.add(createOneToOneProperty(entityType, field));
                continue;
            }
            if (field.isAnnotationPresent(EmbeddedId.class)) {
                if (field.isAnnotationPresent(Id.class)) {
                    throw new IllegalArgumentException(
                            entityType.getName() + "." + field.getName()
                                    + " cannot declare both @Id and @EmbeddedId");
                }
                if (idProperty != null) {
                    throw new IllegalArgumentException(
                            entityType.getName() + " declares multiple @Id/@EmbeddedId properties");
                }
                // @EmbeddedId는 @Embeddable holder를 컬럼들로 펼친 뒤 각 컴포넌트를 복합키 id로 표시한다.
                List<PersistentProperty> components = createEmbeddedIdProperties(entityType, field);
                for (PersistentProperty idComponent : components) {
                    properties.add(idComponent);
                    if (idProperty == null) {
                        idProperty = idComponent;
                    }
                }
                continue;
            }
            if (field.isAnnotationPresent(Embedded.class)) {
                List<PersistentProperty> expanded = createEmbeddedProperties(
                        entityType, field, List.of(), "", new LinkedHashSet<>());
                properties.addAll(expanded);
                continue;
            }
            PersistentProperty property = createProperty(entityType, field, List.of(), "");
            properties.add(property);
            if (property.id()) {
                if (idProperty != null) {
                    throw new IllegalArgumentException(entityType.getName() + " declares multiple @Id properties");
                }
                idProperty = property;
            }
            if (property.createdAt()) {
                if (property.id()) {
                    throw new IllegalArgumentException(
                            entityType.getName() + "." + property.propertyName() + " cannot be both @Id and @CreatedAt");
                }
                if (!SUPPORTED_AUDIT_TYPES.contains(property.javaType())) {
                    throw new IllegalArgumentException(
                            "Unsupported audit type " + property.javaType().getName()
                                    + " on " + entityType.getName() + "." + property.propertyName()
                                    + "; supported: Instant, LocalDateTime, OffsetDateTime");
                }
                if (createdAtProperty != null) {
                    throw new IllegalArgumentException(entityType.getName() + " declares multiple @CreatedAt properties");
                }
                createdAtProperty = property;
            }
            if (property.updatedAt()) {
                if (property.id()) {
                    throw new IllegalArgumentException(
                            entityType.getName() + "." + property.propertyName() + " cannot be both @Id and @UpdatedAt");
                }
                if (!SUPPORTED_AUDIT_TYPES.contains(property.javaType())) {
                    throw new IllegalArgumentException(
                            "Unsupported audit type " + property.javaType().getName()
                                    + " on " + entityType.getName() + "." + property.propertyName()
                                    + "; supported: Instant, LocalDateTime, OffsetDateTime");
                }
                if (updatedAtProperty != null) {
                    throw new IllegalArgumentException(entityType.getName() + " declares multiple @UpdatedAt properties");
                }
                updatedAtProperty = property;
            }
            if (property.softDelete()) {
                if (softDeleteProperty != null) {
                    throw new IllegalArgumentException(entityType.getName() + " declares multiple @SoftDelete properties");
                }
                softDeleteProperty = property;
            }
            if (property.version()) {
                if (versionProperty != null) {
                    throw new IllegalArgumentException(entityType.getName() + " declares multiple @Version properties");
                }
                versionProperty = property;
            }
        }

        if (idProperty == null) {
            throw new IllegalArgumentException(entityType.getName() + " must declare a field annotated with @Id");
        }

        List<Method> prePersistCallbacks = new ArrayList<>();
        List<Method> postPersistCallbacks = new ArrayList<>();
        List<Method> preUpdateCallbacks = new ArrayList<>();
        List<Method> postUpdateCallbacks = new ArrayList<>();
        List<Method> postLoadCallbacks = new ArrayList<>();
        List<Method> preRemoveCallbacks = new ArrayList<>();
        List<Method> postRemoveCallbacks = new ArrayList<>();
        // 콜백은 @MappedSuperclass와 SINGLE_TABLE 상속 상위 @Entity까지 포함해 수집한다 — 루트/베이스에
        // 선언된 audit 콜백이 서브타입에서도 발화하도록. 서브클래스가 같은 메서드를 override하면 가장
        // 하위 정의만 한 번 수집한다(중복 호출 방지).
        Set<String> seenCallbackSignatures = new LinkedHashSet<>();
        for (Method method : mappedMethods(entityType)) {
            if (method.isSynthetic()) {
                continue;
            }
            if (!seenCallbackSignatures.add(callbackSignature(method))) {
                continue;
            }
            collectCallback(entityType, method, PrePersist.class, prePersistCallbacks);
            collectCallback(entityType, method, PostPersist.class, postPersistCallbacks);
            collectCallback(entityType, method, PreUpdate.class, preUpdateCallbacks);
            collectCallback(entityType, method, PostUpdate.class, postUpdateCallbacks);
            collectCallback(entityType, method, PostLoad.class, postLoadCallbacks);
            collectCallback(entityType, method, PreRemove.class, preRemoveCallbacks);
            collectCallback(entityType, method, PostRemove.class, postRemoveCallbacks);
        }

        Set<String> columnNames = new LinkedHashSet<>();
        for (PersistentProperty property : properties) {
            if (property.oneToMany() || property.inverseToOne()) {
                // @OneToMany / inverse @OneToOne은 parent 테이블 컬럼이 없는 marker-only이므로 uniqueness 검증 대상이 아니다.
                continue;
            }
            if (!columnNames.add(property.columnName())) {
                throw new IllegalArgumentException(
                        entityType.getName() + " declares duplicate column '" + property.columnName()
                                + "'; check @Column overrides and @Embedded host field names");
            }
        }
        List<IndexDefinition> indexes = extractIndexes(
                entityType, tableName, columnNames,
                table == null ? new Index[0] : table.indexes());
        List<UniqueConstraintDefinition> uniqueConstraints = extractUniqueConstraints(
                entityType, tableName, columnNames,
                table == null ? new UniqueConstraint[0] : table.uniqueConstraints());

        EntityMetadata<T> metadata = new EntityMetadata<>(
                entityType,
                entityName,
                tableName,
                table != null ? table.schema() : "",
                properties,
                idProperty,
                prePersistCallbacks,
                postPersistCallbacks,
                preUpdateCallbacks,
                postUpdateCallbacks,
                postLoadCallbacks,
                preRemoveCallbacks,
                postRemoveCallbacks,
                indexes,
                uniqueConstraints,
                inheritance
        );
        registerHierarchyMember(metadata);
        return metadata;
    }

    /**
     * SINGLE_TABLE 상속 계층에서 이 엔티티의 위치를 해석한다. 상속에 참여하지 않으면
     * {@link InheritanceInfo#NONE}. 계층은 (a) 이 엔티티가 직접 {@link Inheritance}를 선언했거나
     * (b) 상위에 {@link Entity} 조상이 존재할 때 성립한다(JPA 기본 전략이 SINGLE_TABLE). 루트의
     * {@link Inheritance#strategy()}가 SINGLE_TABLE이 아니면 fail-fast로 거부한다.
     */
    private InheritanceInfo resolveInheritance(Class<?> entityType, String entityName) {
        Class<?> root = entityType;
        Class<?> ancestor = entityType.getSuperclass();
        while (ancestor != null && ancestor != Object.class) {
            if (ancestor.isAnnotationPresent(Entity.class)) {
                root = ancestor;
            }
            ancestor = ancestor.getSuperclass();
        }
        boolean inHierarchy = root != entityType || entityType.isAnnotationPresent(Inheritance.class);
        if (!inHierarchy) {
            return InheritanceInfo.NONE;
        }
        Inheritance rootInheritance = root.getAnnotation(Inheritance.class);
        if (rootInheritance != null && rootInheritance.strategy() != InheritanceType.SINGLE_TABLE) {
            throw new IllegalArgumentException(
                    root.getName() + " uses @Inheritance(strategy=" + rootInheritance.strategy()
                            + ") which Nova does not support; only SINGLE_TABLE is supported");
        }
        DiscriminatorColumn discriminatorColumn = root.getAnnotation(DiscriminatorColumn.class);
        String columnName = discriminatorColumn != null && !discriminatorColumn.name().isBlank()
                ? discriminatorColumn.name()
                : "dtype";
        DiscriminatorType discriminatorType = discriminatorColumn != null
                ? discriminatorColumn.discriminatorType()
                : DiscriminatorType.STRING;
        int discriminatorLength = discriminatorColumn != null ? discriminatorColumn.length() : 31;
        boolean abstractType = Modifier.isAbstract(entityType.getModifiers());
        String discriminatorValue = resolveDiscriminatorValue(
                entityType, entityName, discriminatorType, abstractType);
        return new InheritanceInfo(
                root, root == entityType, abstractType,
                columnName, discriminatorType, discriminatorLength, discriminatorValue);
    }

    /**
     * 이 엔티티의 discriminator 값을 해석한다. {@link DiscriminatorValue}가 있으면 그 값을, 없으면
     * STRING 타입은 JPA 규약대로 entity 이름을 기본값으로 쓴다. CHAR/INTEGER는 기본값이 모호하므로
     * 구체 타입에서는 명시적 {@link DiscriminatorValue}를 요구한다(abstract 타입은 row로 인스턴스화되지
     * 않으므로 빈 값 허용).
     */
    private static String resolveDiscriminatorValue(
            Class<?> entityType, String entityName, DiscriminatorType type, boolean abstractType) {
        DiscriminatorValue annotation = entityType.getAnnotation(DiscriminatorValue.class);
        if (annotation != null && !annotation.value().isBlank()) {
            return annotation.value();
        }
        if (type == DiscriminatorType.STRING) {
            return entityName;
        }
        if (abstractType) {
            return "";
        }
        throw new IllegalArgumentException(
                entityType.getName() + " requires an explicit @DiscriminatorValue because its hierarchy uses"
                        + " DiscriminatorType." + type + " (only STRING has a default value)");
    }

    /**
     * 구체(비-abstract) 계층 멤버를 root 레지스트리에 등록한다. 같은 discriminator 값을 서로 다른 두
     * 타입이 선언하면 fail-fast로 거부한다. 등록 시 해당 root의 병합 메타데이터 캐시를 무효화해, 이후
     * 새 서브타입이 union DDL/select-list에 반영되도록 한다.
     */
    private void registerHierarchyMember(EntityMetadata<?> metadata) {
        InheritanceInfo info = metadata.inheritance();
        if (!info.present() || info.abstractType()) {
            return;
        }
        Map<String, Class<?>> members = hierarchies.computeIfAbsent(
                info.root(), ignored -> new ConcurrentHashMap<>());
        Class<?> previous = members.putIfAbsent(info.discriminatorValue(), metadata.entityType());
        if (previous != null && previous != metadata.entityType()) {
            throw new IllegalArgumentException(
                    "Duplicate @DiscriminatorValue '" + info.discriminatorValue() + "' in hierarchy "
                            + info.root().getName() + ": " + previous.getName() + " and "
                            + metadata.entityType().getName());
        }
        mergedHierarchyCache.remove(info.root());
    }

    /**
     * {@link Table#indexes()}에 선언된 {@link Index}를 모아 검증 후 {@link IndexDefinition}으로 변환한다.
     * 이름이 비어있으면 {@code ix_{table}_{col1}_{col2}_...} 패턴으로 자동 생성한다.
     * {@link Index#columnList()}는 JPA와 동일하게 콤마로 구분한 컬럼 이름 목록이다.
     */
    private static List<IndexDefinition> extractIndexes(
            Class<?> entityType,
            String tableName,
            Set<String> columnNames,
            Index[] declarations
    ) {
        if (declarations.length == 0) {
            return List.of();
        }
        List<IndexDefinition> result = new ArrayList<>(declarations.length);
        for (Index declaration : declarations) {
            String[] columns = parseColumnList(declaration.columnList());
            if (columns.length == 0) {
                throw new IllegalArgumentException(
                        entityType.getName() + " @Index must declare at least one column");
            }
            validateColumnsExist(entityType, "@Index", columns, columnNames);
            String name = declaration.name().isBlank()
                    ? autoGenerateName("ix_", tableName, columns)
                    : declaration.name();
            result.add(new IndexDefinition(name, List.of(columns)));
        }
        return result;
    }

    /**
     * {@link Table#uniqueConstraints()}에 선언된 {@link UniqueConstraint}를 모아 검증 후
     * {@link UniqueConstraintDefinition}으로 변환한다. 이름이 비어있으면
     * {@code uk_{table}_{col1}_{col2}_...} 패턴으로 자동 생성한다.
     */
    private static List<UniqueConstraintDefinition> extractUniqueConstraints(
            Class<?> entityType,
            String tableName,
            Set<String> columnNames,
            UniqueConstraint[] declarations
    ) {
        if (declarations.length == 0) {
            return List.of();
        }
        List<UniqueConstraintDefinition> result = new ArrayList<>(declarations.length);
        for (UniqueConstraint declaration : declarations) {
            String[] columns = declaration.columnNames();
            if (columns == null || columns.length == 0) {
                throw new IllegalArgumentException(
                        entityType.getName() + " @UniqueConstraint must declare at least one column");
            }
            validateColumnsExist(entityType, "@UniqueConstraint", columns, columnNames);
            String name = declaration.name().isBlank()
                    ? autoGenerateName("uk_", tableName, columns)
                    : declaration.name();
            result.add(new UniqueConstraintDefinition(name, List.of(columns)));
        }
        return result;
    }

    /**
     * JPA {@link Index#columnList()} 형식(콤마 구분)을 컬럼 이름 배열로 파싱한다. 각 항목의 공백은
     * 제거하고 빈 항목은 버린다.
     */
    private static String[] parseColumnList(String columnList) {
        if (columnList == null || columnList.isBlank()) {
            return new String[0];
        }
        return Arrays.stream(columnList.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toArray(String[]::new);
    }

    /**
     * jakarta.persistence.Column 중 Nova가 honor하지 않는 속성이 설정되면 metadata 빌드 시점에
     * 명확히 거부한다 ("조용히 무시되는 거짓말 매핑" 방지). name / nullable / length / precision /
     * scale / insertable / updatable / unique / columnDefinition은 honor한다. secondary table 매핑만
     * 지원하지 않는다.
     */
    /**
     * 엔티티 자신의 필드와, 매핑에 기여하는 조상들의 필드를 함께 반환한다. 조상은 {@link MappedSuperclass}
     * (id/audit를 가진 BaseEntity)와 SINGLE_TABLE 상속의 상위 {@link Entity}(루트/중간 엔티티)를 포함한다.
     * 상위 클래스의 필드(루트의 @Id 등)가 먼저 오도록 root-first로 정렬한다.
     */
    private static List<Field> mappedFields(Class<?> entityType) {
        List<Class<?>> chain = new ArrayList<>();
        chain.add(entityType);
        Class<?> ancestor = entityType.getSuperclass();
        while (ancestor != null && ancestor != Object.class
                && (ancestor.isAnnotationPresent(MappedSuperclass.class)
                || ancestor.isAnnotationPresent(Entity.class))) {
            chain.add(ancestor);
            ancestor = ancestor.getSuperclass();
        }
        List<Field> fields = new ArrayList<>();
        for (int i = chain.size() - 1; i >= 0; i--) {
            fields.addAll(Arrays.asList(chain.get(i).getDeclaredFields()));
        }
        return fields;
    }

    /**
     * 엔티티 자신과 매핑에 기여하는 조상({@link MappedSuperclass} / 상위 {@link Entity})의 선언 메서드를
     * 서브클래스-우선(most-derived first) 순서로 반환한다. override 판별은 호출부에서 시그니처 dedupe로
     * 처리하므로, 더 하위에 선언된 override가 먼저 보이도록 entityType부터 위로 올라가며 수집한다.
     */
    private static List<Method> mappedMethods(Class<?> entityType) {
        List<Method> methods = new ArrayList<>();
        Class<?> current = entityType;
        while (current != null && current != Object.class
                && (current == entityType
                || current.isAnnotationPresent(MappedSuperclass.class)
                || current.isAnnotationPresent(Entity.class))) {
            methods.addAll(Arrays.asList(current.getDeclaredMethods()));
            current = current.getSuperclass();
        }
        return methods;
    }

    /**
     * override된 콜백을 한 번만 수집하기 위한 메서드 시그니처 키(이름 + 파라미터 타입). 콜백은 no-arg가
     * 강제되므로 사실상 이름만으로 충분하지만, 일반성을 위해 파라미터 타입까지 포함한다.
     */
    private static String callbackSignature(Method method) {
        StringBuilder builder = new StringBuilder(method.getName()).append('(');
        for (Class<?> parameterType : method.getParameterTypes()) {
            builder.append(parameterType.getName()).append(',');
        }
        return builder.append(')').toString();
    }

    /**
     * 영속 대상이 아닌 필드인지 판정한다. synthetic / static / Java {@code transient} 키워드뿐 아니라
     * JPA {@link Transient} 애너테이션이 붙은 필드도 매핑에서 제외한다.
     */
    private static boolean isNotPersistable(Field field) {
        return field.isSynthetic()
                || Modifier.isStatic(field.getModifiers())
                || Modifier.isTransient(field.getModifiers())
                || field.isAnnotationPresent(Transient.class);
    }

    /**
     * {@code @GeneratedValue(generator=...)}의 논리 이름을, 같은 필드 또는 엔티티 타입에 선언된
     * {@link SequenceGenerator}(이름이 일치하는 것)의 {@code sequenceName}으로 해석한다. 매칭되는
     * {@code @SequenceGenerator}가 없으면 generator 값을 그대로(시퀀스 이름으로) 반환한다.
     * {@code allocationSize}/{@code initialValue}는 Nova가 매 INSERT마다 nextval만 호출하므로 무시된다.
     */
    private static String resolveSequenceName(Class<?> declaringType, Field field, String generatorName) {
        SequenceGenerator sg = field.getAnnotation(SequenceGenerator.class);
        if (sg == null || !sg.name().equals(generatorName)) {
            SequenceGenerator onType = declaringType.getAnnotation(SequenceGenerator.class);
            sg = onType != null && onType.name().equals(generatorName) ? onType : null;
        }
        if (sg == null) {
            return generatorName;
        }
        return sg.sequenceName().isBlank() ? sg.name() : sg.sequenceName();
    }

    private static void rejectUnsupportedColumnAttributes(Class<?> declaringType, Field field, Column column) {
        if (!column.table().isBlank()) {
            throw new IllegalArgumentException(
                    declaringType.getName() + "." + field.getName()
                            + " @Column(table=...) (secondary tables) is not supported");
        }
    }

    private static void validateColumnsExist(
            Class<?> entityType,
            String annotationLabel,
            String[] columns,
            Set<String> knownColumns
    ) {
        for (String column : columns) {
            if (column == null || column.isBlank()) {
                throw new IllegalArgumentException(
                        entityType.getName() + " " + annotationLabel
                                + " contains a blank column name");
            }
            if (!knownColumns.contains(column)) {
                throw new IllegalArgumentException(
                        entityType.getName() + " " + annotationLabel
                                + " references unknown column '" + column
                                + "'; known columns: " + knownColumns);
            }
        }
    }

    /**
     * 가장 좁은 PostgreSQL 식별자 한도(63자)에 맞춰, dialect별 별도 길이 분기 없이 단일 상한을
     * 사용한다. MySQL 한도(64자)도 자동으로 충족된다.
     */
    private static final int MAX_AUTO_GENERATED_NAME_LENGTH = 63;

    /**
     * {@code {prefix}{table}_{col1}_{col2}_...} 패턴으로 index/unique constraint 이름을 만든다.
     * 결과가 63자(PostgreSQL identifier 한도)를 초과하면 {@code _<hex hash>} suffix가 항상
     * 결과에 포함되도록 prefix 부분을 먼저 잘라서 hash 변별력을 보존한다 — 동일 table에서
     * columns만 다른 두 index가 같은 prefix 63자를 공유할 때도 충돌하지 않는다.
     */
    private static String autoGenerateName(String prefix, String tableName, String[] columns) {
        StringBuilder builder = new StringBuilder(prefix).append(tableName);
        for (String column : columns) {
            builder.append('_').append(column);
        }
        String full = builder.toString();
        if (full.length() <= MAX_AUTO_GENERATED_NAME_LENGTH) {
            return full;
        }
        int hash = Objects.hash(tableName, Arrays.hashCode(columns));
        String suffix = "_" + Integer.toHexString(hash);
        String prefixPart = prefix + tableName;
        int budget = MAX_AUTO_GENERATED_NAME_LENGTH - suffix.length();
        if (prefixPart.length() > budget) {
            prefixPart = prefixPart.substring(0, budget);
        }
        return prefixPart + suffix;
    }

    /**
     * {@code @Embedded} 필드를 호스트 엔티티 컬럼으로 펼친 {@link PersistentProperty} 목록을 만든다.
     * sub-property가 다시 {@code @Embedded}이면 재귀적으로 펼치며, 컬럼 이름은
     * {@code {outer host snake_case}_{inner host snake_case}_..._{leaf property columnName}}
     * 패턴으로 합성된다. sub-property는 {@code @Id}/{@code @Version}/{@code @SoftDelete}/
     * {@code @CreatedAt}/{@code @UpdatedAt}을 가질 수 없다.
     * <p>
     * cycle detection: outer @Embedded host type들의 stack({@code embeddableStack})에 현재 host 타입이
     * 이미 존재하면 무한 재귀를 의미하므로 즉시 {@link IllegalArgumentException}으로 거부한다.
     *
     * @param parentHostPath outer → inner 순서로 누적된 @Embedded host field chain
     * @param parentColumnPrefix 누적된 컬럼 prefix(끝에 {@code _} 포함)
     * @param embeddableStack 현재 재귀 경로에 있는 @Embeddable 타입 집합 (cycle 검출용)
     */
    /**
     * {@code @EmbeddedId} 필드를 복합키 컬럼들로 펼친다. {@code @Embedded}와 달리 컬럼 이름에 host 필드
     * 이름 prefix를 붙이지 않는다 — JPA는 {@code @EmbeddedId} 컴포넌트를 그 자신의 컬럼 이름(또는
     * host 필드의 {@code @AttributeOverride})으로 직접 매핑한다. 각 컴포넌트는 {@link PersistentProperty#withId()}로
     * id 표시되며, read/write를 위한 embedded host path는 {@code @EmbeddedId} holder 필드 하나다.
     * 컴포넌트는 application-assigned이므로 {@code @GeneratedValue}나 중첩 embedded를 가질 수 없다.
     */
    private List<PersistentProperty> createEmbeddedIdProperties(Class<?> entityType, Field idField) {
        Class<?> embeddableType = idField.getType();
        if (!embeddableType.isAnnotationPresent(Embeddable.class)) {
            throw new IllegalArgumentException(
                    entityType.getName() + "." + idField.getName()
                            + " is annotated with @EmbeddedId but its type " + embeddableType.getName()
                            + " is not annotated with @Embeddable");
        }
        List<Field> hostPath = List.of(idField);
        // @EmbeddedId host 필드의 @AttributeOverride(name=..., column=@Column(name=...))로 컴포넌트 컬럼명을 재정의한다.
        Map<String, String> columnOverrides = new java.util.HashMap<>();
        for (AttributeOverride override : idField.getAnnotationsByType(AttributeOverride.class)) {
            columnOverrides.put(override.name(), override.column().name());
        }
        List<PersistentProperty> result = new ArrayList<>();
        for (Field subField : embeddableType.getDeclaredFields()) {
            if (isNotPersistable(subField)) {
                continue;
            }
            if (subField.isAnnotationPresent(Embedded.class) || subField.isAnnotationPresent(EmbeddedId.class)) {
                throw new IllegalArgumentException(
                        entityType.getName() + "." + idField.getName()
                                + " @EmbeddedId component " + subField.getName()
                                + " must be a simple (non-embedded) field");
            }
            // columnPrefix=""로 호출해 host 필드 이름 prefix 없이 컴포넌트 컬럼 이름을 그대로 쓴다.
            PersistentProperty component = createProperty(
                    embeddableType, subField, hostPath, "", columnOverrides.get(subField.getName()));
            if (component.generated()) {
                throw new IllegalArgumentException(
                        entityType.getName() + "." + idField.getName()
                                + " @EmbeddedId component " + subField.getName()
                                + " cannot use @GeneratedValue; composite keys are application-assigned");
            }
            result.add(component.withId());
        }
        if (result.isEmpty()) {
            throw new IllegalArgumentException(
                    entityType.getName() + "." + idField.getName()
                            + " @EmbeddedId type " + embeddableType.getName()
                            + " has no persistent fields to map as key columns");
        }
        return result;
    }

    private List<PersistentProperty> createEmbeddedProperties(
            Class<?> entityType,
            Field hostField,
            List<Field> parentHostPath,
            String parentColumnPrefix,
            LinkedHashSet<Class<?>> embeddableStack
    ) {
        Class<?> embeddableType = hostField.getType();
        if (!embeddableType.isAnnotationPresent(Embeddable.class)) {
            throw new IllegalArgumentException(
                    entityType.getName() + "." + hostField.getName()
                            + " is annotated with @Embedded but its type " + embeddableType.getName()
                            + " is not annotated with @Embeddable");
        }
        if (embeddableStack.contains(embeddableType)) {
            throw new IllegalArgumentException(
                    "circular @Embedded detected on " + entityType.getName()
                            + ": type " + embeddableType.getName()
                            + " transitively embeds itself via " + describeEmbeddableStack(embeddableStack, embeddableType));
        }
        if (hasIdAnnotatedField(embeddableType)) {
            throw new IllegalArgumentException(
                    "@Embeddable type " + embeddableType.getName()
                            + " must not declare @Id-annotated fields");
        }
        String columnPrefix = parentColumnPrefix + namingStrategy.columnName(hostField.getName()) + "_";
        List<Field> hostPath = new ArrayList<>(parentHostPath.size() + 1);
        hostPath.addAll(parentHostPath);
        hostPath.add(hostField);
        List<Field> immutableHostPath = List.copyOf(hostPath);
        // @AttributeOverride(name="city", column=@Column(name="ship_city")) — 이 @Embedded 호스트 필드에
        // 선언된 override를 immediate sub-property 이름 기준으로 모은다. 컬럼 name만 적용한다.
        Map<String, String> columnOverrides = new java.util.HashMap<>();
        for (AttributeOverride override : hostField.getAnnotationsByType(AttributeOverride.class)) {
            columnOverrides.put(override.name(), override.column().name());
        }
        List<PersistentProperty> result = new ArrayList<>();
        embeddableStack.add(embeddableType);
        try {
            for (Field subField : embeddableType.getDeclaredFields()) {
                if (isNotPersistable(subField)) {
                    continue;
                }
                rejectIllegalSubFieldAnnotations(entityType, hostField, embeddableType, subField);
                if (subField.isAnnotationPresent(Embedded.class)) {
                    // nested @Embedded는 재귀적으로 펼친다. host path와 column prefix는 이 단계에서 한 번 확장된 값을 넘긴다.
                    List<PersistentProperty> nested = createEmbeddedProperties(
                            entityType, subField, immutableHostPath, columnPrefix, embeddableStack);
                    result.addAll(nested);
                    continue;
                }
                PersistentProperty property = createProperty(
                        embeddableType, subField, immutableHostPath, columnPrefix,
                        columnOverrides.get(subField.getName()));
                result.add(property);
            }
        } finally {
            embeddableStack.remove(embeddableType);
        }
        return result;
    }

    private static String describeEmbeddableStack(LinkedHashSet<Class<?>> stack, Class<?> repeated) {
        StringBuilder builder = new StringBuilder();
        for (Class<?> type : stack) {
            builder.append(type.getSimpleName()).append(" -> ");
        }
        builder.append(repeated.getSimpleName());
        return builder.toString();
    }

    private static boolean hasIdAnnotatedField(Class<?> embeddableType) {
        for (Field field : embeddableType.getDeclaredFields()) {
            if (isNotPersistable(field)) {
                continue;
            }
            if (field.isAnnotationPresent(Id.class)) {
                return true;
            }
        }
        return false;
    }

    private static void rejectIllegalSubFieldAnnotations(
            Class<?> entityType,
            Field hostField,
            Class<?> embeddableType,
            Field subField
    ) {
        String location = entityType.getName() + "." + hostField.getName()
                + " (@Embedded " + embeddableType.getSimpleName() + "." + subField.getName() + ")";
        if (subField.isAnnotationPresent(Id.class)) {
            throw new IllegalArgumentException(location + " must not declare @Id");
        }
        if (subField.isAnnotationPresent(Version.class)) {
            throw new IllegalArgumentException(location + " must not declare @Version");
        }
        if (subField.isAnnotationPresent(SoftDelete.class)) {
            throw new IllegalArgumentException(location + " must not declare @SoftDelete");
        }
        if (subField.isAnnotationPresent(CreatedAt.class)) {
            throw new IllegalArgumentException(location + " must not declare @CreatedAt");
        }
        if (subField.isAnnotationPresent(UpdatedAt.class)) {
            throw new IllegalArgumentException(location + " must not declare @UpdatedAt");
        }
    }

    /**
     * 콜백 어노테이션이 붙은 메서드의 시그니처를 검증한 뒤 컬렉터에 추가한다. 검증 실패 시
     * {@link IllegalArgumentException}을 던지며, 통과한 메서드는 {@code setAccessible(true)}로
     * 한 번만 열어 invoker가 매 호출마다 접근 검사를 반복하지 않게 한다.
     */
    private static <A extends Annotation> void collectCallback(
            Class<?> entityType,
            Method method,
            Class<A> annotationType,
            List<Method> target
    ) {
        if (!method.isAnnotationPresent(annotationType)) {
            return;
        }
        String label = "@" + annotationType.getSimpleName();
        if (Modifier.isStatic(method.getModifiers())) {
            throw new IllegalArgumentException(
                    label + " method " + entityType.getName() + "." + method.getName()
                            + " must be non-static, no-arg, void-returning");
        }
        if (method.getParameterCount() != 0) {
            throw new IllegalArgumentException(
                    label + " method " + entityType.getName() + "." + method.getName()
                            + " must be non-static, no-arg, void-returning");
        }
        if (method.getReturnType() != void.class) {
            throw new IllegalArgumentException(
                    label + " method " + entityType.getName() + "." + method.getName()
                            + " must be non-static, no-arg, void-returning");
        }
        method.setAccessible(true);
        target.add(method);
    }

    /**
     * 단일 field로부터 {@link PersistentProperty}를 만든다. {@code hostPath}가 비어있지 않으면
     * 이 property는 {@code @Embedded} 필드(들) 안에 있는 sub-field이며 column 이름에 prefix가 붙고
     * property name은 호스트 필드 이름들을 dot으로 join한 prefix를 갖는다.
     */
    private PersistentProperty createProperty(
            Class<?> declaringType,
            Field field,
            List<Field> hostPath,
            String columnPrefix
    ) {
        return createProperty(declaringType, field, hostPath, columnPrefix, null);
    }

    /**
     * {@code columnNameOverride}가 비어있지 않으면 prefix/naming을 무시하고 그 이름을 컬럼 이름으로 쓴다.
     * {@code @Embedded} 호스트 필드의 {@code @AttributeOverride}가 sub-property 컬럼명을 재정의할 때 사용된다.
     */
    private PersistentProperty createProperty(
            Class<?> declaringType,
            Field field,
            List<Field> hostPath,
            String columnPrefix,
            String columnNameOverride
    ) {
        Column column = field.getAnnotation(Column.class);
        if (column != null) {
            rejectUnsupportedColumnAttributes(declaringType, field, column);
        }
        GeneratedValue generatedValue = field.getAnnotation(GeneratedValue.class);
        boolean isId = field.isAnnotationPresent(Id.class);
        boolean isSoftDelete = field.isAnnotationPresent(SoftDelete.class);
        if (isSoftDelete) {
            if (isId) {
                throw new IllegalArgumentException(
                        declaringType.getName() + " field " + field.getName()
                                + " cannot be annotated with both @Id and @SoftDelete");
            }
            if (!SUPPORTED_SOFT_DELETE_TYPES.contains(field.getType())) {
                throw new IllegalArgumentException(
                        declaringType.getName() + " field " + field.getName()
                                + " has unsupported @SoftDelete type " + field.getType().getName()
                                + "; supported types are java.time.Instant, java.time.LocalDateTime, java.time.OffsetDateTime");
            }
        }
        boolean isVersion = field.isAnnotationPresent(Version.class);
        if (isVersion) {
            if (isId) {
                throw new IllegalArgumentException(
                        declaringType.getName() + "." + field.getName() + " cannot be both @Id and @Version");
            }
            if (!SUPPORTED_VERSION_TYPES.contains(field.getType())) {
                throw new IllegalArgumentException(
                        "Unsupported version type " + field.getType().getName() + " on "
                                + declaringType.getName() + "." + field.getName()
                                + "; supported types are Long, Integer, Short");
            }
        }
        GenerationType generationType = generatedValue == null ? null : generatedValue.strategy();
        String generator = generatedValue == null ? "" : generatedValue.generator();
        if (generatedValue != null) {
            if (generationType == GenerationType.TABLE) {
                throw new IllegalArgumentException(
                        declaringType.getName() + "." + field.getName()
                                + " uses @GeneratedValue(TABLE) which Nova does not support;"
                                + " use IDENTITY, SEQUENCE, UUID, or AUTO");
            }
            if (generationType == GenerationType.SEQUENCE) {
                if (!isId) {
                    throw new IllegalArgumentException(
                            declaringType.getName() + "." + field.getName()
                                    + " uses @GeneratedValue(SEQUENCE) but is not annotated with @Id");
                }
                if (generator == null || generator.isBlank()) {
                    throw new IllegalArgumentException(
                            declaringType.getName() + "." + field.getName()
                                    + " uses @GeneratedValue(SEQUENCE) but does not specify generator (sequence name)");
                }
                // JPA: @GeneratedValue(generator="name")가 @SequenceGenerator(name="name", sequenceName=...)를
                // 가리키면 그 sequenceName으로 해석한다. 매칭되는 @SequenceGenerator가 없으면 generator 값을
                // 시퀀스 이름으로 그대로 사용한다(shorthand).
                generator = resolveSequenceName(declaringType, field, generator);
                if (!SEQUENCE_GENERATOR_NAME_PATTERN.matcher(generator).matches()) {
                    throw new IllegalArgumentException(
                            "Invalid sequence generator name: '" + generator + "' on "
                                    + declaringType.getName() + "." + field.getName()
                                    + " — must match identifier pattern "
                                    + SEQUENCE_GENERATOR_NAME_PATTERN.pattern());
                }
            }
            if (generationType == GenerationType.UUID) {
                if (!isId) {
                    throw new IllegalArgumentException(
                            declaringType.getName() + "." + field.getName()
                                    + " uses @GeneratedValue(UUID) but is not annotated with @Id");
                }
                if (!SUPPORTED_UUID_ID_TYPES.contains(field.getType())) {
                    throw new IllegalArgumentException(
                            "Unsupported UUID id type " + field.getType().getName() + " on "
                                    + declaringType.getName() + "." + field.getName()
                                    + "; supported types are java.util.UUID, java.lang.String");
                }
            }
        }
        String baseColumnName = column != null && !column.name().isBlank()
                ? column.name()
                : namingStrategy.columnName(field.getName());
        String columnName = columnNameOverride != null && !columnNameOverride.isBlank()
                ? columnNameOverride
                : columnPrefix + baseColumnName;
        String propertyName;
        if (hostPath == null || hostPath.isEmpty()) {
            propertyName = field.getName();
        } else {
            StringBuilder builder = new StringBuilder();
            for (Field hostField : hostPath) {
                builder.append(hostField.getName()).append('.');
            }
            builder.append(field.getName());
            propertyName = builder.toString();
        }

        Enumerated enumerated = field.getAnnotation(Enumerated.class);
        boolean isJson = field.isAnnotationPresent(Json.class);
        AttributeConverter<?, ?> userConverter = converters.get(field.getType());
        boolean isEnumerated = false;
        EnumType enumType = null;
        AttributeConverter<?, ?> converter = userConverter;
        if (enumerated != null) {
            if (isJson) {
                throw new IllegalStateException(
                        declaringType.getName() + "." + field.getName()
                                + " cannot declare both @Json and @Enumerated");
            }
            if (!field.getType().isEnum()) {
                throw new IllegalArgumentException(
                        declaringType.getName() + "." + field.getName()
                                + " is annotated with @Enumerated but its type "
                                + field.getType().getName() + " is not an enum");
            }
            if (userConverter != null) {
                throw new IllegalArgumentException(
                        declaringType.getName() + "." + field.getName()
                                + " cannot use both @Enumerated and a registered AttributeConverter for "
                                + field.getType().getName());
            }
            isEnumerated = true;
            enumType = enumerated.value();
            converter = createEnumConverter(field.getType(), enumType);
        }
        if (isJson) {
            if (userConverter != null) {
                throw new IllegalStateException(
                        declaringType.getName() + "." + field.getName()
                                + " cannot use both @Json and a registered AttributeConverter for "
                                + field.getType().getName());
            }
            // @Json은 dialect의 jsonColumnType()으로 컬럼 타입을 받고, 값 변환은 주입된 JsonCodec을 감싼
            // JsonAttributeConverter로 일반 converter 경로(toColumnValue/toPropertyValue)를 그대로 탄다.
            converter = new JsonAttributeConverter(jsonCodec, field.getType());
        }

        // @Convert(converter=X.class): JPA 표준 AttributeConverter를 어댑터로 감싸 일반 converter 경로에 태운다.
        // 저장 표현 타입(Y)을 columnType()/schema 컬럼 타입의 근거로 보관한다.
        Class<?> converterColumnType = null;
        Convert convert = field.getAnnotation(Convert.class);
        if (convert != null && !convert.disableConversion()) {
            if (isEnumerated || isJson) {
                throw new IllegalStateException(
                        declaringType.getName() + "." + field.getName()
                                + " cannot combine @Convert with @Enumerated/@Json");
            }
            if (userConverter != null) {
                throw new IllegalStateException(
                        declaringType.getName() + "." + field.getName()
                                + " cannot combine @Convert with a registered AttributeConverter for "
                                + field.getType().getName());
            }
            Class<?> converterClass = convert.converter();
            if (converterClass == void.class || converterClass == Void.class) {
                throw new IllegalArgumentException(
                        declaringType.getName() + "." + field.getName()
                                + " @Convert requires a converter class");
            }
            Class<?>[] attributeAndColumn = resolveJpaConverterTypeArguments(declaringType, field, converterClass);
            Class<?> attributeType = attributeAndColumn[0];
            Class<?> fieldType = wrapPrimitiveType(field.getType());
            if (!attributeType.isAssignableFrom(fieldType) && !fieldType.isAssignableFrom(attributeType)) {
                throw new IllegalArgumentException(
                        declaringType.getName() + "." + field.getName()
                                + " @Convert converter " + converterClass.getName()
                                + " expects attribute type " + attributeType.getName()
                                + " but the field is " + field.getType().getName());
            }
            converter = new JpaAttributeConverterAdapter<>(instantiateJpaConverter(converterClass));
            converterColumnType = attributeAndColumn[1];
        }

        boolean embedded = hostPath != null && !hostPath.isEmpty();
        int length = column != null ? column.length() : 255;
        int precision = column != null ? column.precision() : 0;
        int scale = column != null ? column.scale() : 0;
        boolean insertable = column == null || column.insertable();
        boolean updatable = column == null || column.updatable();
        boolean unique = column != null && column.unique();
        String columnDefinition = column == null ? "" : column.columnDefinition();
        boolean lob = field.isAnnotationPresent(Lob.class);
        return new PersistentProperty(
                field,
                propertyName,
                columnName,
                field.getType(),
                isId,
                isVersion,
                column == null || column.nullable(),
                length,
                precision,
                scale,
                generationType,
                generator,
                converter,
                field.isAnnotationPresent(CreatedAt.class),
                field.isAnnotationPresent(UpdatedAt.class),
                isSoftDelete,
                embedded,
                embedded ? hostPath : List.of(),
                isEnumerated,
                enumType,
                isJson,
                false,
                null,
                true,
                false,
                null,
                "",
                insertable,
                updatable,
                unique,
                columnDefinition,
                lob,
                converterColumnType,
                false
        );
    }

    /**
     * {@code @Convert}로 지정된 {@link jakarta.persistence.AttributeConverter} 구현의 type argument
     * {@code [X(엔티티 속성 타입), Y(컬럼 저장 타입)]}을 reflection으로 해석한다. 구체 타입이 아니거나
     * (raw/제네릭) AttributeConverter 구현이 발견되지 않으면 fail-fast로 거부한다.
     */
    private static Class<?>[] resolveJpaConverterTypeArguments(
            Class<?> declaringType, Field field, Class<?> converterClass) {
        for (Type supertype : genericSupertypes(converterClass)) {
            if (supertype instanceof ParameterizedType parameterized
                    && parameterized.getRawType() == jakarta.persistence.AttributeConverter.class) {
                Type[] arguments = parameterized.getActualTypeArguments();
                Class<?> attributeType = rawClass(arguments[0]);
                Class<?> columnType = rawClass(arguments[1]);
                if (attributeType == null || columnType == null) {
                    break;
                }
                return new Class<?>[]{attributeType, columnType};
            }
        }
        throw new IllegalArgumentException(
                declaringType.getName() + "." + field.getName() + " @Convert converter "
                        + converterClass.getName()
                        + " must implement jakarta.persistence.AttributeConverter with concrete type arguments");
    }

    /**
     * 클래스의 제네릭 상위 타입(구현 인터페이스 + 슈퍼클래스)을 재귀적으로 평탄화해 반환한다.
     * {@code AttributeConverter}를 중간 추상 베이스를 통해 구현한 경우까지 탐색한다.
     */
    private static List<Type> genericSupertypes(Class<?> type) {
        List<Type> result = new ArrayList<>();
        Class<?> current = type;
        while (current != null && current != Object.class) {
            result.addAll(Arrays.asList(current.getGenericInterfaces()));
            Type genericSuperclass = current.getGenericSuperclass();
            if (genericSuperclass != null) {
                result.add(genericSuperclass);
            }
            current = current.getSuperclass();
        }
        return result;
    }

    private static Class<?> rawClass(Type type) {
        if (type instanceof Class<?> clazz) {
            return clazz;
        }
        if (type instanceof ParameterizedType parameterized
                && parameterized.getRawType() instanceof Class<?> raw) {
            return raw;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static jakarta.persistence.AttributeConverter<Object, Object> instantiateJpaConverter(
            Class<?> converterClass) {
        try {
            var constructor = converterClass.getDeclaredConstructor();
            constructor.setAccessible(true);
            return (jakarta.persistence.AttributeConverter<Object, Object>) constructor.newInstance();
        } catch (ReflectiveOperationException exception) {
            throw new IllegalArgumentException(
                    "@Convert converter " + converterClass.getName()
                            + " must have an accessible no-arg constructor", exception);
        }
    }

    /**
     * primitive 타입을 대응하는 boxed wrapper로 바꾼다(@Convert 속성 타입 호환 비교용). primitive가 아니면
     * 그대로 반환한다.
     */
    private static Class<?> wrapPrimitiveType(Class<?> type) {
        if (!type.isPrimitive()) {
            return type;
        }
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == boolean.class) return Boolean.class;
        if (type == double.class) return Double.class;
        if (type == float.class) return Float.class;
        if (type == short.class) return Short.class;
        if (type == byte.class) return Byte.class;
        if (type == char.class) return Character.class;
        return type;
    }

    /**
     * 같은 필드에 관계 어노테이션과 양립 불가능한 다른 어노테이션이 함께 선언된 경우를 거부한다.
     * 검증은 {@link OneToMany}/{@link ManyToOne} 한쪽이라도 존재할 때만 수행한다.
     */
    private static void rejectIncompatibleRelationAnnotations(Class<?> entityType, Field field) {
        boolean isManyToOne = field.isAnnotationPresent(ManyToOne.class);
        boolean isOneToMany = field.isAnnotationPresent(OneToMany.class);
        boolean isOneToOne = field.isAnnotationPresent(OneToOne.class);
        int relationCount = (isManyToOne ? 1 : 0) + (isOneToMany ? 1 : 0) + (isOneToOne ? 1 : 0);
        if (relationCount == 0) {
            return;
        }
        String location = entityType.getName() + "." + field.getName();
        if (relationCount > 1) {
            throw new IllegalStateException(
                    location + " cannot declare more than one of @ManyToOne / @OneToMany / @OneToOne");
        }
        if (field.isAnnotationPresent(Embedded.class)) {
            throw new IllegalStateException(location + " cannot declare @Embedded together with a relation annotation");
        }
        if (field.isAnnotationPresent(Id.class)) {
            throw new IllegalStateException(location + " cannot declare @Id together with a relation annotation");
        }
        if (field.isAnnotationPresent(Version.class)) {
            throw new IllegalStateException(location + " cannot declare @Version together with a relation annotation");
        }
        if (field.isAnnotationPresent(SoftDelete.class)) {
            throw new IllegalStateException(location + " cannot declare @SoftDelete together with a relation annotation");
        }
        if (field.isAnnotationPresent(CreatedAt.class)) {
            throw new IllegalStateException(location + " cannot declare @CreatedAt together with a relation annotation");
        }
        if (field.isAnnotationPresent(UpdatedAt.class)) {
            throw new IllegalStateException(location + " cannot declare @UpdatedAt together with a relation annotation");
        }
        if (field.isAnnotationPresent(Enumerated.class)) {
            throw new IllegalStateException(location + " cannot declare @Enumerated together with a relation annotation");
        }
        if (field.isAnnotationPresent(Json.class)) {
            throw new IllegalStateException(location + " cannot declare @Json together with a relation annotation");
        }
    }

    /**
     * {@link OneToMany} marker-only property를 만든다. parent 테이블 컬럼이 없으므로 column-related
     * 메타데이터는 비워두고, mappedBy와 target type만 보존한다.
     */
    private PersistentProperty createOneToManyProperty(Class<?> entityType, Field field) {
        OneToMany annotation = field.getAnnotation(OneToMany.class);
        if (annotation.cascade().length > 0) {
            throw new IllegalArgumentException(
                    entityType.getName() + "." + field.getName()
                            + " @OneToMany(cascade=...) is not supported; persist children explicitly via save/saveAll");
        }
        if (annotation.orphanRemoval()) {
            throw new IllegalArgumentException(
                    entityType.getName() + "." + field.getName()
                            + " @OneToMany(orphanRemoval=true) is not supported; delete children explicitly");
        }
        String mappedBy = annotation.mappedBy();
        if (mappedBy == null || mappedBy.isBlank()) {
            throw new IllegalStateException(
                    entityType.getName() + "." + field.getName()
                            + " @OneToMany requires non-blank mappedBy");
        }
        Class<?> targetType = annotation.targetEntity();
        if (targetType == void.class) {
            // erasure로 컬렉션의 원소 타입을 직접 추론할 수 없으면 null로 두고 호출자가 명시할 수 있게 한다.
            targetType = null;
        }
        return new PersistentProperty(
                field,
                field.getName(),
                "", // no column for inverse side
                field.getType(),
                false,
                false,
                true,
                255,
                0,
                0,
                null,
                "",
                null,
                false,
                false,
                false,
                false,
                List.of(),
                false,
                null,
                false,
                false,
                null,
                true,
                true,
                targetType,
                mappedBy,
                true,
                true,
                false,
                "",
                false,
                null,
                false
        );
    }

    /**
     * {@link ManyToOne} owning property를 만든다. FK 컬럼 이름은 {@link JoinColumn#name()} 또는
     * 기본 naming strategy로 {@code <propertyName>_id} 형태가 된다. javaType은 FK 컬럼이 보관하는
     * 식별자 타입이지만 target entity 메타데이터에 의존하지 않기 위해 일단 {@link Long}으로 fallback한다 —
     * mapRow는 이 property를 직접 read/write하지 않으므로(관계는 FetchGroup이 채워준다) javaType 정확도가
     * row decoding에 영향을 주지 않는다.
     */
    private PersistentProperty createManyToOneProperty(Class<?> entityType, Field field) {
        ManyToOne manyToOne = field.getAnnotation(ManyToOne.class);
        if (manyToOne.fetch() == FetchType.LAZY) {
            throw new IllegalArgumentException(
                    entityType.getName() + "." + field.getName()
                            + " @ManyToOne(fetch=LAZY) is not supported; Nova has no lazy proxy."
                            + " Use the default eager fetch or drive a FetchGroup explicitly");
        }
        if (manyToOne.cascade().length > 0) {
            throw new IllegalArgumentException(
                    entityType.getName() + "." + field.getName()
                            + " @ManyToOne(cascade=...) is not supported; persist the parent explicitly");
        }
        JoinColumn joinColumn = field.getAnnotation(JoinColumn.class);
        Class<?> targetType = manyToOne.targetEntity();
        if (targetType == void.class) {
            targetType = field.getType();
        }
        String columnName;
        if (joinColumn != null && !joinColumn.name().isBlank()) {
            columnName = joinColumn.name();
        } else {
            columnName = namingStrategy.columnName(field.getName() + "_id");
        }
        boolean nullable = manyToOne.optional() && (joinColumn == null || joinColumn.nullable());
        boolean fkInsertable = joinColumn == null || joinColumn.insertable();
        boolean fkUpdatable = joinColumn == null || joinColumn.updatable();
        boolean fkUnique = joinColumn != null && joinColumn.unique();
        String fkColumnDefinition = joinColumn == null ? "" : joinColumn.columnDefinition();
        return new PersistentProperty(
                field,
                field.getName(),
                columnName,
                Long.class,
                false,
                false,
                nullable,
                255,
                0,
                0,
                null,
                "",
                null,
                false,
                false,
                false,
                false,
                List.of(),
                false,
                null,
                false,
                true,
                targetType,
                nullable,
                false,
                null,
                "",
                fkInsertable,
                fkUpdatable,
                fkUnique,
                fkColumnDefinition,
                false,
                null,
                false
        );
    }

    /**
     * {@link OneToOne} property를 만든다. {@code mappedBy}가 없으면 owning side로 FK 컬럼을 가지며
     * {@code @ManyToOne}과 동일한 단건 참조 메커니즘({@code manyToOne=true})으로 모델링한다(FK는 unique 기본).
     * {@code mappedBy}가 있으면 inverse side로 컬럼 없는 {@code inverseToOne} 마커가 되고, 소유 측 FK로
     * 단건 child가 hydration된다. fetch=LAZY와 cascade는 거부한다.
     */
    private PersistentProperty createOneToOneProperty(Class<?> entityType, Field field) {
        OneToOne oneToOne = field.getAnnotation(OneToOne.class);
        if (oneToOne.fetch() == FetchType.LAZY) {
            throw new IllegalArgumentException(
                    entityType.getName() + "." + field.getName()
                            + " @OneToOne(fetch=LAZY) is not supported; Nova has no lazy proxy."
                            + " Use the default eager fetch or drive a FetchGroup explicitly");
        }
        if (oneToOne.cascade().length > 0) {
            throw new IllegalArgumentException(
                    entityType.getName() + "." + field.getName()
                            + " @OneToOne(cascade=...) is not supported; persist the related entity explicitly");
        }
        Class<?> targetType = oneToOne.targetEntity();
        if (targetType == void.class) {
            targetType = field.getType();
        }
        String mappedBy = oneToOne.mappedBy();
        if (mappedBy != null && !mappedBy.isBlank()) {
            // inverse side — 컬럼 없는 마커. target/mappedBy는 oneToMany 필드 자리에 보관하고 inverseToOne로 구분한다.
            return new PersistentProperty(
                    field,
                    field.getName(),
                    "",
                    field.getType(),
                    false,
                    false,
                    true,
                    255,
                    0,
                    0,
                    null,
                    "",
                    null,
                    false,
                    false,
                    false,
                    false,
                    List.of(),
                    false,
                    null,
                    false,
                    false,
                    null,
                    false,
                    false,
                    targetType,
                    mappedBy,
                    true,
                    true,
                    false,
                    "",
                    false,
                    null,
                    true
            );
        }
        // owning side — FK 컬럼을 가지는 단건 참조. @ManyToOne과 동일하게 모델링하되 FK는 unique 기본.
        JoinColumn joinColumn = field.getAnnotation(JoinColumn.class);
        String columnName;
        if (joinColumn != null && !joinColumn.name().isBlank()) {
            columnName = joinColumn.name();
        } else {
            columnName = namingStrategy.columnName(field.getName() + "_id");
        }
        boolean nullable = oneToOne.optional() && (joinColumn == null || joinColumn.nullable());
        boolean fkInsertable = joinColumn == null || joinColumn.insertable();
        boolean fkUpdatable = joinColumn == null || joinColumn.updatable();
        // @OneToOne의 FK는 일대일을 강제하기 위해 unique로 emit한다(@JoinColumn(unique=false)는 무시).
        boolean fkUnique = true;
        String fkColumnDefinition = joinColumn == null ? "" : joinColumn.columnDefinition();
        return new PersistentProperty(
                field,
                field.getName(),
                columnName,
                Long.class,
                false,
                false,
                nullable,
                255,
                0,
                0,
                null,
                "",
                null,
                false,
                false,
                false,
                false,
                List.of(),
                false,
                null,
                false,
                true,
                targetType,
                nullable,
                false,
                null,
                "",
                fkInsertable,
                fkUpdatable,
                fkUnique,
                fkColumnDefinition,
                false,
                null,
                false
        );
    }

    private static AttributeConverter<?, ?> createEnumConverter(Class<?> enumClass, EnumType enumType) {
        @SuppressWarnings({"unchecked", "rawtypes"})
        Class<? extends Enum> raw = (Class<? extends Enum>) enumClass;
        return createEnumConverterTyped(raw, enumType);
    }

    private static <E extends Enum<E>> AttributeConverter<E, ?> createEnumConverterTyped(
            Class<E> enumClass, EnumType enumType) {
        return switch (enumType) {
            case STRING -> new EnumStringConverter<>(enumClass);
            case ORDINAL -> new EnumOrdinalConverter<>(enumClass);
        };
    }
}
