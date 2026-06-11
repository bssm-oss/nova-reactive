package io.nova.metadata;

import io.nova.annotation.Column;
import io.nova.annotation.CreatedAt;
import io.nova.annotation.Embeddable;
import io.nova.annotation.Embedded;
import io.nova.annotation.Entity;
import io.nova.annotation.EnumType;
import io.nova.annotation.Enumerated;
import io.nova.annotation.GeneratedValue;
import io.nova.annotation.GenerationType;
import io.nova.annotation.Id;
import io.nova.annotation.Index;
import io.nova.annotation.JoinColumn;
import io.nova.annotation.Json;
import io.nova.annotation.ManyToOne;
import io.nova.annotation.OneToMany;
import io.nova.annotation.PostLoad;
import io.nova.annotation.PostPersist;
import io.nova.annotation.PostRemove;
import io.nova.annotation.PostUpdate;
import io.nova.annotation.PrePersist;
import io.nova.annotation.PreRemove;
import io.nova.annotation.PreUpdate;
import io.nova.annotation.SoftDelete;
import io.nova.annotation.Table;
import io.nova.annotation.UniqueConstraint;
import io.nova.annotation.UpdatedAt;
import io.nova.annotation.Version;
import io.nova.convert.AttributeConverter;
import io.nova.convert.EnumOrdinalConverter;
import io.nova.convert.EnumStringConverter;
import io.nova.convert.JsonAttributeConverter;
import io.nova.json.JsonCodec;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
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

    private <T> EntityMetadata<T> createMetadata(Class<T> entityType) {
        Entity entity = entityType.getAnnotation(Entity.class);
        if (entity == null) {
            throw new IllegalArgumentException(entityType.getName() + " is not annotated with @Entity");
        }

        Table table = entityType.getAnnotation(Table.class);
        String entityName = entity.name().isBlank() ? entityType.getSimpleName() : entity.name();
        String tableName = table != null && !table.name().isBlank() ? table.name() : namingStrategy.tableName(entityType);

        List<PersistentProperty> properties = new ArrayList<>();
        PersistentProperty idProperty = null;
        PersistentProperty createdAtProperty = null;
        PersistentProperty updatedAtProperty = null;
        PersistentProperty softDeleteProperty = null;
        PersistentProperty versionProperty = null;
        for (Field field : entityType.getDeclaredFields()) {
            if (field.isSynthetic() || Modifier.isStatic(field.getModifiers()) || Modifier.isTransient(field.getModifiers())) {
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
        for (Method method : entityType.getDeclaredMethods()) {
            if (method.isSynthetic()) {
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
            if (property.oneToMany()) {
                // @OneToMany는 parent 테이블 컬럼이 없는 marker-only property로, column uniqueness 검증 대상이 아니다.
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

        return new EntityMetadata<>(
                entityType,
                entityName,
                tableName,
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
                uniqueConstraints
        );
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
        List<PersistentProperty> result = new ArrayList<>();
        embeddableStack.add(embeddableType);
        try {
            for (Field subField : embeddableType.getDeclaredFields()) {
                if (subField.isSynthetic() || Modifier.isStatic(subField.getModifiers())) {
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
                PersistentProperty property = createProperty(embeddableType, subField, immutableHostPath, columnPrefix);
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
            if (field.isSynthetic() || Modifier.isStatic(field.getModifiers()) || Modifier.isTransient(field.getModifiers())) {
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
        Column column = field.getAnnotation(Column.class);
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
        String columnName = columnPrefix + baseColumnName;
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

        boolean embedded = hostPath != null && !hostPath.isEmpty();
        int length = column != null ? column.length() : 255;
        int precision = column != null ? column.precision() : 0;
        int scale = column != null ? column.scale() : 0;
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
                ""
        );
    }

    /**
     * 같은 필드에 관계 어노테이션과 양립 불가능한 다른 어노테이션이 함께 선언된 경우를 거부한다.
     * 검증은 {@link OneToMany}/{@link ManyToOne} 한쪽이라도 존재할 때만 수행한다.
     */
    private static void rejectIncompatibleRelationAnnotations(Class<?> entityType, Field field) {
        boolean isManyToOne = field.isAnnotationPresent(ManyToOne.class);
        boolean isOneToMany = field.isAnnotationPresent(OneToMany.class);
        if (!isManyToOne && !isOneToMany) {
            return;
        }
        if (isManyToOne && isOneToMany) {
            throw new IllegalStateException(
                    entityType.getName() + "." + field.getName()
                            + " cannot declare both @ManyToOne and @OneToMany");
        }
        String location = entityType.getName() + "." + field.getName();
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
                mappedBy
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
                ""
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
