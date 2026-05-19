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
import io.nova.annotation.PostLoad;
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
    private final Map<Class<?>, EntityMetadata<?>> cache = new ConcurrentHashMap<>();
    private final Map<Class<?>, AttributeConverter<?, ?>> converters = new ConcurrentHashMap<>();

    public EntityMetadataFactory(NamingStrategy namingStrategy) {
        this.namingStrategy = namingStrategy;
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
        String tableName = table != null && !table.value().isBlank() ? table.value() : namingStrategy.tableName(entityType);

        List<PersistentProperty> properties = new ArrayList<>();
        PersistentProperty idProperty = null;
        PersistentProperty createdAtProperty = null;
        PersistentProperty updatedAtProperty = null;
        PersistentProperty softDeleteProperty = null;
        PersistentProperty versionProperty = null;
        for (Field field : entityType.getDeclaredFields()) {
            if (field.isSynthetic() || Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            if (field.isAnnotationPresent(Embedded.class)) {
                List<PersistentProperty> expanded = createEmbeddedProperties(entityType, field);
                properties.addAll(expanded);
                continue;
            }
            PersistentProperty property = createProperty(entityType, field, null, "");
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
        List<Method> preUpdateCallbacks = new ArrayList<>();
        List<Method> postLoadCallbacks = new ArrayList<>();
        List<Method> preRemoveCallbacks = new ArrayList<>();
        for (Method method : entityType.getDeclaredMethods()) {
            if (method.isSynthetic()) {
                continue;
            }
            collectCallback(entityType, method, PrePersist.class, prePersistCallbacks);
            collectCallback(entityType, method, PreUpdate.class, preUpdateCallbacks);
            collectCallback(entityType, method, PostLoad.class, postLoadCallbacks);
            collectCallback(entityType, method, PreRemove.class, preRemoveCallbacks);
        }

        Set<String> columnNames = new LinkedHashSet<>();
        for (PersistentProperty property : properties) {
            if (!columnNames.add(property.columnName())) {
                throw new IllegalArgumentException(
                        entityType.getName() + " declares duplicate column '" + property.columnName()
                                + "'; check @Column overrides and @Embedded host field names");
            }
        }
        List<IndexDefinition> indexes = extractIndexes(entityType, tableName, columnNames);
        List<UniqueConstraintDefinition> uniqueConstraints =
                extractUniqueConstraints(entityType, tableName, columnNames);

        return new EntityMetadata<>(
                entityType,
                entityName,
                tableName,
                properties,
                idProperty,
                prePersistCallbacks,
                preUpdateCallbacks,
                postLoadCallbacks,
                preRemoveCallbacks,
                indexes,
                uniqueConstraints
        );
    }

    /**
     * 타입에 선언된 {@link Index} 어노테이션(반복 포함)을 모아 검증 후 {@link IndexDefinition}으로 변환한다.
     * 이름이 비어있으면 {@code ix_{table}_{col1}_{col2}_...} 패턴으로 자동 생성한다.
     */
    private static List<IndexDefinition> extractIndexes(
            Class<?> entityType,
            String tableName,
            Set<String> columnNames
    ) {
        Index[] declarations = entityType.getAnnotationsByType(Index.class);
        if (declarations.length == 0) {
            return List.of();
        }
        List<IndexDefinition> result = new ArrayList<>(declarations.length);
        for (Index declaration : declarations) {
            String[] columns = declaration.columns();
            if (columns == null || columns.length == 0) {
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
     * 타입에 선언된 {@link UniqueConstraint} 어노테이션(반복 포함)을 모아 검증 후
     * {@link UniqueConstraintDefinition}으로 변환한다. 이름이 비어있으면
     * {@code uk_{table}_{col1}_{col2}_...} 패턴으로 자동 생성한다.
     */
    private static List<UniqueConstraintDefinition> extractUniqueConstraints(
            Class<?> entityType,
            String tableName,
            Set<String> columnNames
    ) {
        UniqueConstraint[] declarations = entityType.getAnnotationsByType(UniqueConstraint.class);
        if (declarations.length == 0) {
            return List.of();
        }
        List<UniqueConstraintDefinition> result = new ArrayList<>(declarations.length);
        for (UniqueConstraint declaration : declarations) {
            String[] columns = declaration.columns();
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
     * 컬럼 이름은 {@code {hostField snake_case}_{sub-property columnName}} 패턴으로 합성되며
     * sub-property는 {@code @Id}/{@code @Version}/{@code @SoftDelete}/{@code @CreatedAt}/
     * {@code @UpdatedAt}을 가질 수 없고 nested {@code @Embedded}도 허용하지 않는다.
     */
    private List<PersistentProperty> createEmbeddedProperties(Class<?> entityType, Field hostField) {
        Class<?> embeddableType = hostField.getType();
        if (!embeddableType.isAnnotationPresent(Embeddable.class)) {
            throw new IllegalArgumentException(
                    entityType.getName() + "." + hostField.getName()
                            + " is annotated with @Embedded but its type " + embeddableType.getName()
                            + " is not annotated with @Embeddable");
        }
        if (hasIdAnnotatedField(embeddableType)) {
            throw new IllegalArgumentException(
                    "@Embeddable type " + embeddableType.getName()
                            + " must not declare @Id-annotated fields");
        }
        String columnPrefix = namingStrategy.columnName(hostField.getName()) + "_";
        List<PersistentProperty> result = new ArrayList<>();
        for (Field subField : embeddableType.getDeclaredFields()) {
            if (subField.isSynthetic() || Modifier.isStatic(subField.getModifiers())) {
                continue;
            }
            rejectIllegalSubFieldAnnotations(entityType, hostField, embeddableType, subField);
            PersistentProperty property = createProperty(embeddableType, subField, hostField, columnPrefix);
            result.add(property);
        }
        return result;
    }

    private static boolean hasIdAnnotatedField(Class<?> embeddableType) {
        for (Field field : embeddableType.getDeclaredFields()) {
            if (field.isSynthetic() || Modifier.isStatic(field.getModifiers())) {
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
        if (subField.isAnnotationPresent(Embedded.class)) {
            throw new IllegalArgumentException(location + " must not declare a nested @Embedded; only one level is supported");
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
     * 단일 field로부터 {@link PersistentProperty}를 만든다. {@code hostField}가 null이 아니면
     * 이 property는 {@code @Embedded} 필드 안에 있는 sub-field이며 column 이름에 prefix가 붙는다.
     */
    private PersistentProperty createProperty(
            Class<?> declaringType,
            Field field,
            Field hostField,
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
        GenerationType generationType = generatedValue == null ? GenerationType.NONE : generatedValue.strategy();
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
        String baseColumnName = column != null && !column.value().isBlank()
                ? column.value()
                : namingStrategy.columnName(field.getName());
        String columnName = columnPrefix + baseColumnName;
        String propertyName = hostField == null
                ? field.getName()
                : hostField.getName() + "." + field.getName();

        Enumerated enumerated = field.getAnnotation(Enumerated.class);
        AttributeConverter<?, ?> userConverter = converters.get(field.getType());
        boolean isEnumerated = false;
        EnumType enumType = null;
        AttributeConverter<?, ?> converter = userConverter;
        if (enumerated != null) {
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

        return new PersistentProperty(
                field,
                propertyName,
                columnName,
                field.getType(),
                isId,
                isVersion,
                column == null || column.nullable(),
                generationType,
                generator,
                converter,
                field.isAnnotationPresent(CreatedAt.class),
                field.isAnnotationPresent(UpdatedAt.class),
                isSoftDelete,
                hostField != null,
                hostField,
                isEnumerated,
                enumType
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
