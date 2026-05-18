package io.nova.metadata;

import io.nova.annotation.Column;
import io.nova.annotation.CreatedAt;
import io.nova.annotation.Entity;
import io.nova.annotation.GeneratedValue;
import io.nova.annotation.GenerationType;
import io.nova.annotation.Id;
import io.nova.annotation.PostLoad;
import io.nova.annotation.PrePersist;
import io.nova.annotation.PreRemove;
import io.nova.annotation.PreUpdate;
import io.nova.annotation.SoftDelete;
import io.nova.annotation.Table;
import io.nova.annotation.UpdatedAt;
import io.nova.annotation.Version;
import io.nova.convert.AttributeConverter;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
            PersistentProperty property = createProperty(entityType, field);
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

        return new EntityMetadata<>(
                entityType,
                entityName,
                tableName,
                properties,
                idProperty,
                prePersistCallbacks,
                preUpdateCallbacks,
                postLoadCallbacks,
                preRemoveCallbacks
        );
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

    private PersistentProperty createProperty(Class<?> entityType, Field field) {
        Column column = field.getAnnotation(Column.class);
        GeneratedValue generatedValue = field.getAnnotation(GeneratedValue.class);
        boolean isId = field.isAnnotationPresent(Id.class);
        boolean isSoftDelete = field.isAnnotationPresent(SoftDelete.class);
        if (isSoftDelete) {
            if (isId) {
                throw new IllegalArgumentException(
                        entityType.getName() + " field " + field.getName()
                                + " cannot be annotated with both @Id and @SoftDelete");
            }
            if (!SUPPORTED_SOFT_DELETE_TYPES.contains(field.getType())) {
                throw new IllegalArgumentException(
                        entityType.getName() + " field " + field.getName()
                                + " has unsupported @SoftDelete type " + field.getType().getName()
                                + "; supported types are java.time.Instant, java.time.LocalDateTime, java.time.OffsetDateTime");
            }
        }
        boolean isVersion = field.isAnnotationPresent(Version.class);
        if (isVersion) {
            if (isId) {
                throw new IllegalArgumentException(
                        entityType.getName() + "." + field.getName() + " cannot be both @Id and @Version");
            }
            if (!SUPPORTED_VERSION_TYPES.contains(field.getType())) {
                throw new IllegalArgumentException(
                        "Unsupported version type " + field.getType().getName() + " on "
                                + entityType.getName() + "." + field.getName()
                                + "; supported types are Long, Integer, Short");
            }
        }
        GenerationType generationType = generatedValue == null ? GenerationType.NONE : generatedValue.strategy();
        String generator = generatedValue == null ? "" : generatedValue.generator();
        if (generatedValue != null) {
            if (generationType == GenerationType.SEQUENCE) {
                if (!isId) {
                    throw new IllegalArgumentException(
                            entityType.getName() + "." + field.getName()
                                    + " uses @GeneratedValue(SEQUENCE) but is not annotated with @Id");
                }
                if (generator == null || generator.isBlank()) {
                    throw new IllegalArgumentException(
                            entityType.getName() + "." + field.getName()
                                    + " uses @GeneratedValue(SEQUENCE) but does not specify generator (sequence name)");
                }
                if (!SEQUENCE_GENERATOR_NAME_PATTERN.matcher(generator).matches()) {
                    throw new IllegalArgumentException(
                            "Invalid sequence generator name: '" + generator + "' on "
                                    + entityType.getName() + "." + field.getName()
                                    + " — must match identifier pattern "
                                    + SEQUENCE_GENERATOR_NAME_PATTERN.pattern());
                }
            }
            if (generationType == GenerationType.UUID) {
                if (!isId) {
                    throw new IllegalArgumentException(
                            entityType.getName() + "." + field.getName()
                                    + " uses @GeneratedValue(UUID) but is not annotated with @Id");
                }
                if (!SUPPORTED_UUID_ID_TYPES.contains(field.getType())) {
                    throw new IllegalArgumentException(
                            "Unsupported UUID id type " + field.getType().getName() + " on "
                                    + entityType.getName() + "." + field.getName()
                                    + "; supported types are java.util.UUID, java.lang.String");
                }
            }
        }
        String columnName = column != null && !column.value().isBlank()
                ? column.value()
                : namingStrategy.columnName(field.getName());

        return new PersistentProperty(
                field,
                field.getName(),
                columnName,
                field.getType(),
                isId,
                isVersion,
                column == null || column.nullable(),
                generationType,
                generator,
                converters.get(field.getType()),
                field.isAnnotationPresent(CreatedAt.class),
                field.isAnnotationPresent(UpdatedAt.class),
                isSoftDelete
        );
    }
}
